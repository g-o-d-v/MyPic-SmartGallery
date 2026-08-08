package com.goda.mypic;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    /*
     * Android 10+ 直接调用系统 MediaStore 缩略图接口。
     * 这条路径和系统相册更接近：优先复用系统已经生成的 thumbnail，而不是让 Glide
     * 在用户快速跳到几千张后重新排队解码大量原图。
     */
    private static final ExecutorService THUMBNAIL_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final int THUMB_CACHE_KB = Math.max(16 * 1024,
            (int) (Runtime.getRuntime().maxMemory() / 1024L / 12L));
    private static final LruCache<String, Bitmap> THUMBNAIL_CACHE =
            new LruCache<String, Bitmap>(THUMB_CACHE_KB) {
                @Override
                protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
                    return Math.max(1, value.getAllocationByteCount() / 1024);
                }
            };

    private final Context context;
    private final List<MediaItem> mediaList;
    private final int thumbnailSizePx;
    private final int systemThumbnailSizePx;
    private volatile boolean fastScrollActive = false;

    public boolean isMultiSelectMode = false;
    public Set<MediaItem> selectedItems = new HashSet<>();
    private final OnMediaInteractionListener listener;

    public interface OnMediaInteractionListener {
        void onSingleClick(MediaItem item);
        void onSelectionChanged(int count);
        void onStartDragSelect(int position);
    }

    public MediaAdapter(Context context, List<MediaItem> mediaList, OnMediaInteractionListener listener) {
        this.context = context.getApplicationContext();
        this.mediaList = mediaList;
        this.listener = listener;
        this.thumbnailSizePx = Math.max(96, context.getResources().getDisplayMetrics().widthPixels / 4);
        // 4 列网格在常见 1080/1440p 设备上用 256~320px 缩略图已经足够。
        this.systemThumbnailSizePx = Math.max(192, Math.min(320, thumbnailSizePx));
        setHasStableIds(true);
    }

    public void setFastScrollActive(boolean active) {
        fastScrollActive = active;
    }

    // 全选/取消全选逻辑
    public void selectAll(boolean select) {
        selectedItems.clear();
        if (select) selectedItems.addAll(mediaList);
        notifyDataSetChanged();
    }

    public void clearSelection() {
        isMultiSelectMode = false;
        selectedItems.clear();
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return mediaList.get(position).stableId();
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaItem item = mediaList.get(position);

        ViewGroup.LayoutParams layoutParams = holder.ivThumbnail.getLayoutParams();
        if (layoutParams.height != thumbnailSizePx) {
            layoutParams.height = thumbnailSizePx;
            holder.ivThumbnail.setLayoutParams(layoutParams);
        }

        bindThumbnail(holder, item);
        bindTypeTag(holder, item);
        bindSelectionState(holder, item);
        bindInteractions(holder);
    }

    private void bindThumbnail(@NonNull MediaViewHolder holder, @NonNull MediaItem item) {
        cancelThumbnailLoad(holder);

        String cacheKey = item.uri + "|" + item.dateModified + "|" + systemThumbnailSizePx;
        holder.boundThumbnailKey = cacheKey;

        Bitmap cached = THUMBNAIL_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            holder.ivThumbnail.setImageBitmap(cached);
            return;
        }

        // 快速拖动右侧进度条时，不给沿途位置排几百个无意义的解码任务。
        // 松手后 MainActivity 只会重新绑定最终可见区域。
        if (fastScrollActive) {
            holder.ivThumbnail.setImageDrawable(null);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(item.uri.getScheme())) {
            loadSystemThumbnail(holder, item, cacheKey);
        } else {
            loadGlideThumbnail(holder, item, cacheKey);
        }
    }

    private void loadSystemThumbnail(@NonNull MediaViewHolder holder,
                                     @NonNull MediaItem item,
                                     @NonNull String cacheKey) {
        CancellationSignal signal = new CancellationSignal();
        holder.cancellationSignal = signal;

        holder.thumbnailFuture = THUMBNAIL_EXECUTOR.submit(() -> {
            try {
                Bitmap bitmap = context.getContentResolver().loadThumbnail(
                        item.uri,
                        new Size(systemThumbnailSizePx, systemThumbnailSizePx),
                        signal
                );

                if (bitmap == null || signal.isCanceled()) return;
                THUMBNAIL_CACHE.put(cacheKey, bitmap);

                holder.itemView.post(() -> {
                    if (!cacheKey.equals(holder.boundThumbnailKey) || fastScrollActive) return;
                    holder.ivThumbnail.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // 某些第三方 ContentProvider 不实现 loadThumbnail，自动回落 Glide。
                holder.itemView.post(() -> {
                    if (cacheKey.equals(holder.boundThumbnailKey) && !fastScrollActive) {
                        loadGlideThumbnail(holder, item, cacheKey);
                    }
                });
            }
        });
    }

    private void loadGlideThumbnail(@NonNull MediaViewHolder holder,
                                    @NonNull MediaItem item,
                                    @NonNull String cacheKey) {
        if (!cacheKey.equals(holder.boundThumbnailKey) || fastScrollActive) return;

        Glide.with(holder.itemView)
                .asBitmap()
                .load(item.uri)
                .override(systemThumbnailSizePx, systemThumbnailSizePx)
                .centerCrop()
                .format(DecodeFormat.PREFER_RGB_565)
                .priority(Priority.HIGH)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .dontAnimate()
                .into(holder.ivThumbnail);
    }

    private void bindTypeTag(@NonNull MediaViewHolder holder, @NonNull MediaItem item) {
        if (item.type == MediaItem.MediaType.GIF) {
            holder.tvTypeTag.setVisibility(View.VISIBLE);
            holder.tvTypeTag.setText("GIF");
        } else if (item.type == MediaItem.MediaType.VIDEO) {
            holder.tvTypeTag.setVisibility(View.VISIBLE);
            holder.tvTypeTag.setText("VIDEO");
        } else {
            holder.tvTypeTag.setVisibility(View.GONE);
        }
    }

    private void bindSelectionState(@NonNull MediaViewHolder holder, @NonNull MediaItem item) {
        if (isMultiSelectMode) {
            holder.ivCheck.setVisibility(View.VISIBLE);
            if (selectedItems.contains(item)) {
                holder.ivCheck.setAlpha(1.0f);
                holder.ivThumbnail.setScaleX(0.85f);
                holder.ivThumbnail.setScaleY(0.85f);
            } else {
                holder.ivCheck.setAlpha(0.2f);
                holder.ivThumbnail.setScaleX(1.0f);
                holder.ivThumbnail.setScaleY(1.0f);
            }
        } else {
            holder.ivCheck.setVisibility(View.GONE);
            holder.ivThumbnail.setScaleX(1.0f);
            holder.ivThumbnail.setScaleY(1.0f);
        }
    }

    private void bindInteractions(@NonNull MediaViewHolder holder) {
        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= mediaList.size()) return;
            MediaItem currentItem = mediaList.get(currentPosition);
            if (isMultiSelectMode) {
                if (selectedItems.contains(currentItem)) selectedItems.remove(currentItem);
                else selectedItems.add(currentItem);
                notifyItemChanged(currentPosition);
                listener.onSelectionChanged(selectedItems.size());
            } else {
                listener.onSingleClick(currentItem);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= mediaList.size()) return true;
            MediaItem currentItem = mediaList.get(currentPosition);
            if (!isMultiSelectMode) {
                isMultiSelectMode = true;
                selectedItems.add(currentItem);
                notifyDataSetChanged();
            } else if (!selectedItems.contains(currentItem)) {
                selectedItems.add(currentItem);
                notifyItemChanged(currentPosition);
            }
            listener.onStartDragSelect(currentPosition);
            listener.onSelectionChanged(selectedItems.size());
            return true;
        });
    }

    private void cancelThumbnailLoad(@NonNull MediaViewHolder holder) {
        holder.boundThumbnailKey = null;
        if (holder.cancellationSignal != null) {
            try {
                holder.cancellationSignal.cancel();
            } catch (Exception ignored) {}
            holder.cancellationSignal = null;
        }
        Future<?> future = holder.thumbnailFuture;
        if (future != null) {
            future.cancel(true);
            holder.thumbnailFuture = null;
        }
        Glide.with(holder.itemView).clear(holder.ivThumbnail);
    }

    @Override
    public void onViewRecycled(@NonNull MediaViewHolder holder) {
        cancelThumbnailLoad(holder);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return mediaList.size();
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumbnail, ivCheck;
        TextView tvTypeTag;
        String boundThumbnailKey;
        CancellationSignal cancellationSignal;
        Future<?> thumbnailFuture;

        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            ivCheck = itemView.findViewById(R.id.ivCheck);
            tvTypeTag = itemView.findViewById(R.id.tvTypeTag);
        }
    }
}
