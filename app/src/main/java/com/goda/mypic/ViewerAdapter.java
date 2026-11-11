package com.goda.mypic;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class ViewerAdapter extends RecyclerView.Adapter<ViewerAdapter.ViewerVH> {

    private static final String TAG = "ViewerAdapter";

    // 单张 Bitmap 走 Glide 直出的上限；超过此尺寸/像素/文件体积的图片走分块高清渲染，
    // 既杜绝 GL_MAX_TEXTURE_SIZE 超限导致的纯黑与 OOM，又保证长图按原生宽度清晰显示。
    private static final int MAX_SINGLE_TEXTURE_SIDE = 4080;
    private static final long SAFE_SINGLE_BITMAP_PIXELS = 16_000_000L;
    private static final long SAFE_FALLBACK_PIXELS = 12_000_000L;
    private static final long SAFE_SINGLE_FILE_BYTES = 12L * 1024L * 1024L;

    private static final ExecutorService VIEWER_INIT_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile int sMaxTextureSize = 0;
    private static final Object EGL_QUERY_LOCK = new Object();

    private final Context context;
    private final ContentResolver resolver;
    private final List<MediaItem> items;
    private final OnViewerItemClickListener listener;

    public interface OnViewerItemClickListener {
        void onImageClick();
        void onVideoPlayClick(MediaItem item);
    }

    public ViewerAdapter(Context context, List<MediaItem> items, OnViewerItemClickListener listener) {
        this.context = context;
        this.resolver = context.getApplicationContext().getContentResolver();
        this.items = items;
        this.listener = listener;

        // 后台提前探测一次 GPU 纹理上限，避免在 UI 线程创建 EGL 上下文
        if (sMaxTextureSize <= 0) {
            VIEWER_INIT_EXECUTOR.execute(ViewerAdapter::ensureMaxTextureSizeQueried);
        }
    }

    private static void ensureMaxTextureSizeQueried() {
        if (sMaxTextureSize > 0) return;
        synchronized (EGL_QUERY_LOCK) {
            if (sMaxTextureSize <= 0) {
                sMaxTextureSize = queryMaxTextureSize();
                Log.i(TAG, "设备 GL_MAX_TEXTURE_SIZE = " + sMaxTextureSize);
            }
        }
    }

    private static int getKnownMaxTextureSize() {
        int cached = sMaxTextureSize;
        return cached > 0 ? cached : 4096;
    }

    static int computeSafeMaxSingleSide(int glMaxTextureSize) {
        int hwLimit = glMaxTextureSize > 0 ? (int) (glMaxTextureSize * 0.95f) : 3840;
        return Math.min(MAX_SINGLE_TEXTURE_SIDE, Math.max(2048, hwLimit));
    }

    static int computeSafeFallbackSide(int glMaxTextureSize) {
        int hwLimit = glMaxTextureSize > 0 ? (int) (glMaxTextureSize * 0.95f) : 3840;
        return Math.max(2048, hwLimit);
    }

    private static int queryMaxTextureSize() {
        int maxSize = 4096;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return maxSize;

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return maxSize;

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                EGL14.eglTerminate(display);
                return maxSize;
            }

            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglTerminate(display);
                return maxSize;
            }

            int[] surfaceAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroyContext(display, eglContext);
                EGL14.eglTerminate(display);
                return maxSize;
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
                EGL14.eglDestroySurface(display, surface);
                EGL14.eglDestroyContext(display, eglContext);
                EGL14.eglTerminate(display);
                return maxSize;
            }

            int[] result = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, result, 0);
            if (result[0] > 0) {
                maxSize = result[0];
            }
        } catch (Exception e) {
            Log.w(TAG, "查询 GL_MAX_TEXTURE_SIZE 失败，使用默认值 " + maxSize, e);
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
                    EGL14.eglTerminate(display);
                }
            } catch (Exception ignored) {}
        }
        return maxSize;
    }

    @NonNull
    @Override
    public ViewerVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_viewer, parent, false);
        return new ViewerVH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewerVH holder, int position) {
        MediaItem item = items.get(position);

        // 清理前一次绑定的任务与分块缓存，防止复用残影和资源泄漏
        holder.resetForRebind();
        Glide.with(context).clear(holder.photoView);
        holder.photoView.setImageDrawable(null);

        holder.photoView.setLongImageMode(false);
        holder.photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        holder.photoView.setScaleLevels(1.0f, 1.75f, 3.0f);

        if (item.type == MediaItem.MediaType.VIDEO) {
            holder.ivPlayIcon.setVisibility(View.VISIBLE);
            holder.ivPlayIcon.setOnClickListener(v -> {
                if (listener != null) listener.onVideoPlayClick(item);
            });
            loadWithGlide(holder, item, item.width, item.height, false);
        } else {
            holder.ivPlayIcon.setVisibility(View.GONE);
            holder.ivPlayIcon.setOnClickListener(null);

            if (isLikelyGif(item)) {
                loadWithGlide(holder, item, item.width, item.height, true);
            } else if (canUseDirectGlideFastPath(item)) {
                // 普通大小图片走 Glide 原生内存/磁盘缓存直出，保持毫秒级切图速率
                loadWithGlide(holder, item, item.width, item.height, false);
            } else {
                // 大图、长图或外部未知尺寸图片：后台快速探测尺寸并在需要时启用分块渲染
                loadLargeOrUnknownImage(holder, item);
            }
        }

        // 单击触发全屏切换
        holder.photoView.setOnPhotoTapListener((view, x, y) -> {
            if (listener != null) {
                listener.onImageClick();
            }
        });
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewerVH holder) {
        if (holder.activeTiledDrawable != null) {
            // 滑出屏幕的页面保留低分辨率底图以便快速回滑，释放高分辨率切片降低内存占用
            holder.activeTiledDrawable.trimTiles();
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onViewRecycled(@NonNull ViewerVH holder) {
        holder.resetForRebind();
        Glide.with(context).clear(holder.photoView);
        holder.photoView.setImageDrawable(null);
        super.onViewRecycled(holder);
    }

    private static boolean isLikelyGif(@NonNull MediaItem item) {
        if (item.type == MediaItem.MediaType.GIF) return true;
        if ("image/gif".equalsIgnoreCase(item.mimeType)) return true;
        return item.path != null && item.path.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private static boolean canUseDirectGlideFastPath(@NonNull MediaItem item) {
        if (item.width <= 0 || item.height <= 0) return false;
        int safeMaxSingle = computeSafeMaxSingleSide(getKnownMaxTextureSize());
        if (item.width > safeMaxSingle || item.height > safeMaxSingle) return false;
        long totalPixels = (long) item.width * item.height;
        if (totalPixels > SAFE_SINGLE_BITMAP_PIXELS) return false;
        return item.fileSize <= SAFE_SINGLE_FILE_BYTES;
    }

    private void loadLargeOrUnknownImage(@NonNull ViewerVH holder, @NonNull MediaItem item) {
        final long bindGen = holder.bindGeneration;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        holder.pendingCancelFlag = cancelled;

        holder.pendingInitFuture = VIEWER_INIT_EXECUTOR.submit(() -> {
            ensureMaxTextureSizeQueried();
            int safeMaxSingle = computeSafeMaxSingleSide(getKnownMaxTextureSize());

            TiledBitmapDrawable.OpenResult result = TiledBitmapDrawable.open(
                    resolver,
                    item.uri,
                    safeMaxSingle,
                    SAFE_SINGLE_BITMAP_PIXELS,
                    item.fileSize,
                    SAFE_SINGLE_FILE_BYTES,
                    cancelled
            );

            MAIN_HANDLER.post(() -> {
                if (cancelled.get() || holder.bindGeneration != bindGen) {
                    if (result.drawable != null) result.drawable.close();
                    return;
                }

                if (result.kind == TiledBitmapDrawable.OpenResult.Kind.TILED && result.drawable != null) {
                    TiledBitmapDrawable tiled = result.drawable;
                    holder.activeTiledDrawable = tiled;
                    tiled.attachImageView(holder.photoView);
                    holder.photoView.setImageDrawable(tiled);
                    onImageDimensionsReady(holder, bindGen, result.orientedWidth, result.orientedHeight);
                } else if (result.kind == TiledBitmapDrawable.OpenResult.Kind.USE_GLIDE) {
                    loadWithGlide(holder, item, result.orientedWidth, result.orientedHeight, result.animated);
                } else {
                    loadWithGlide(holder, item, item.width, item.height, false);
                }
            });
        });
    }

    private void loadWithGlide(@NonNull ViewerVH holder,
                               @NonNull MediaItem item,
                               int knownWidth,
                               int knownHeight,
                               boolean isAnimated) {
        final long bindGen = holder.bindGeneration;
        int safeTexLimit = computeSafeFallbackSide(getKnownMaxTextureSize());

        RequestBuilder<Drawable> request = Glide.with(context).load(item.uri);

        if (knownWidth > 0 && knownHeight > 0) {
            long totalPixels = (long) knownWidth * knownHeight;
            boolean fitsTex = knownWidth <= safeTexLimit && knownHeight <= safeTexLimit;
            if (fitsTex && (isAnimated || totalPixels <= SAFE_SINGLE_BITMAP_PIXELS)) {
                request = request.override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);
            } else {
                float scale = Math.min(
                        (float) safeTexLimit / knownWidth,
                        (float) safeTexLimit / knownHeight
                );
                if (!isAnimated && totalPixels > SAFE_FALLBACK_PIXELS) {
                    scale = Math.min(scale, (float) Math.sqrt((double) SAFE_FALLBACK_PIXELS / totalPixels));
                }
                int targetW = Math.max(1, Math.round(knownWidth * scale));
                int targetH = Math.max(1, Math.round(knownHeight * scale));
                request = request.override(targetW, targetH).fitCenter();
            }
        } else if (isAnimated) {
            request = request.override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);
        } else {
            request = request.override(safeTexLimit, safeTexLimit).fitCenter();
        }

        request.listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                if (holder.bindGeneration != bindGen) return false;
                final int w = resource.getIntrinsicWidth();
                final int h = resource.getIntrinsicHeight();
                // 必须在 Glide 将 Drawable 设置到 PhotoView 之后再调整缩放，否则 PhotoView 会重置 ScaleType
                holder.photoView.post(() -> onImageDimensionsReady(holder, bindGen, w, h));
                return false;
            }
        }).into(holder.photoView);
    }

    private void onImageDimensionsReady(@NonNull ViewerVH holder, long bindGen, int width, int height) {
        if (holder.bindGeneration != bindGen || width <= 0 || height <= 0) return;

        holder.imgWidth = width;
        holder.imgHeight = height;

        if (holder.photoView.getWidth() > 0 && holder.photoView.getHeight() > 0) {
            applyAppropriateScale(holder);
        } else {
            holder.photoView.post(() -> {
                if (holder.bindGeneration == bindGen) {
                    applyAppropriateScale(holder);
                }
            });
        }
    }

    private void applyAppropriateScale(@NonNull ViewerVH holder) {
        int vw = holder.photoView.getWidth();
        int vh = holder.photoView.getHeight();
        if (vw <= 0 || vh <= 0 || holder.imgWidth <= 0 || holder.imgHeight <= 0) return;

        float imgRatio = (float) holder.imgHeight / holder.imgWidth;
        float viewRatio = (float) vh / vw;

        if (imgRatio > viewRatio * 1.2f) {
            holder.isLongImage = true;
            holder.photoView.setLongImageMode(true);
            applyLongImageScale(holder);
        } else {
            holder.isLongImage = false;
            holder.photoView.setLongImageMode(false);
            float baseScale = Math.min((float) vw / holder.imgWidth, (float) vh / holder.imgHeight);
            if (baseScale > 0f && baseScale < 0.5f) {
                // 超高分辨率大图允许适当放大至接近原图 1:1 像素细节
                float fullDetailScale = 1.0f / baseScale;
                float maxScale = Math.max(3.0f, Math.min(12.0f, fullDetailScale * 1.5f));
                float midScale = Math.max(1.75f, Math.min(maxScale * 0.55f, fullDetailScale * 0.75f));
                if (midScale >= maxScale) midScale = (1.0f + maxScale) * 0.5f;
                holder.photoView.setScaleLevels(1.0f, midScale, maxScale);
            }
        }
    }

    // 🚨 核心拉伸计算方法
    private void applyLongImageScale(ViewerVH holder) {
        int vw = holder.photoView.getWidth();
        int vh = holder.photoView.getHeight();
        if (vw == 0 || vh == 0 || holder.imgWidth == 0 || holder.imgHeight == 0) return;

        // 计算 PhotoView 默认 FIT_START 下的缩放比例
        float baseScale = Math.min((float) vw / holder.imgWidth, (float) vh / holder.imgHeight);
        // 计算如果要撑满屏幕宽度，需要在这个基础上乘以几倍
        float targetSuppScale = ((float) vw / holder.imgWidth) / baseScale;

        // 设置缩放下限，绝不允许图片缩小变窄
        float minScale = targetSuppScale;
        float midScale = targetSuppScale * 1.5f;
        float maxScale = targetSuppScale * 3.0f;

        if (midScale <= minScale) midScale = minScale + 0.1f;
        if (maxScale <= midScale) maxScale = midScale + 0.1f;

        holder.photoView.setScaleType(ImageView.ScaleType.FIT_START); // 对齐顶部
        holder.photoView.setScaleLevels(minScale, midScale, maxScale);

        // 应用倍数，将锚点设在最顶端
        holder.photoView.setScale(targetSuppScale, 0f, 0f, false);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewerVH extends RecyclerView.ViewHolder {
        ViewerPhotoView photoView;
        ImageView ivPlayIcon;

        boolean isLongImage = false;
        int imgWidth = 0;
        int imgHeight = 0;

        long bindGeneration = 0L;
        @Nullable Future<?> pendingInitFuture;
        @Nullable AtomicBoolean pendingCancelFlag;
        @Nullable TiledBitmapDrawable activeTiledDrawable;

        ViewerVH(View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photoView);
            ivPlayIcon = itemView.findViewById(R.id.ivPlayIcon);

            // 🚨 重绘哨兵：废弃一切矩阵还原黑魔法，直接同步重绘！
            photoView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) return;

                if (imgWidth > 0 && imgHeight > 0) {
                    if (isLongImage) {
                        // 注意这里没有用 photoView.post()！
                        // 我们在高度发生变化的同一帧里，同步算出新的比例盖上去，0 毫秒延迟，拒绝一切闪烁和贴边！
                        applyLongImageScale(this);
                    } else {
                        applyAppropriateScale(this);
                    }
                }
            });
        }

        void resetForRebind() {
            bindGeneration++;
            isLongImage = false;
            imgWidth = 0;
            imgHeight = 0;

            if (pendingCancelFlag != null) {
                pendingCancelFlag.set(true);
                pendingCancelFlag = null;
            }
            if (pendingInitFuture != null) {
                pendingInitFuture.cancel(false);
                pendingInitFuture = null;
            }
            if (activeTiledDrawable != null) {
                activeTiledDrawable.close();
                activeTiledDrawable = null;
            }
        }
    }
}
