package com.goda.mypic;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.List;

public class ViewerAdapter extends RecyclerView.Adapter<ViewerAdapter.ViewerVH> {

    private Context context;
    private List<MediaItem> items;
    private OnViewerItemClickListener listener;

    public interface OnViewerItemClickListener {
        void onImageClick();
        void onVideoPlayClick(MediaItem item);
    }

    public ViewerAdapter(Context context, List<MediaItem> items, OnViewerItemClickListener listener) {
        this.context = context;
        this.items = items;
        this.listener = listener;
    }

    // 🚨 防冲突裁判：接管滑动，防止看长图时误翻页
    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);

        recyclerView.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            private float lastX, lastY;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = e.getX();
                        lastY = e.getY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - lastX;
                        float dy = e.getY() - lastY;

                        View child = rv.findChildViewUnder(e.getX(), e.getY());
                        if (child != null) {
                            RecyclerView.ViewHolder viewHolder = rv.getChildViewHolder(child);
                            if (viewHolder instanceof ViewerVH) {
                                PhotoView photoView = ((ViewerVH) viewHolder).photoView;

                                if (photoView.getScale() > 1.0f) {
                                    if (Math.abs(dx) > Math.abs(dy)) {
                                        int direction = dx > 0 ? -1 : 1;
                                        if (photoView.canScrollHorizontally(direction)) {
                                            rv.requestDisallowInterceptTouchEvent(true);
                                        } else {
                                            rv.requestDisallowInterceptTouchEvent(false);
                                        }
                                    } else {
                                        rv.requestDisallowInterceptTouchEvent(true);
                                    }
                                }
                            }
                        }
                        lastX = e.getX();
                        lastY = e.getY();
                        break;
                }
                return false;
            }
            @Override public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {}
            @Override public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        });
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

        // 初始化状态，防止列表复用导致残影
        holder.isLongImage = false;
        holder.imgWidth = 0;
        holder.imgHeight = 0;
        holder.photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        holder.photoView.setMinimumScale(1.0f);
        holder.photoView.setMediumScale(1.75f);
        holder.photoView.setMaximumScale(3.0f);

        // 🚨 加载高清原图
        Glide.with(context)
                .load(item.uri)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        holder.imgWidth = resource.getIntrinsicWidth();
                        holder.imgHeight = resource.getIntrinsicHeight();

                        holder.photoView.post(() -> {
                            int vw = holder.photoView.getWidth();
                            int vh = holder.photoView.getHeight();

                            if (vw > 0 && vh > 0 && holder.imgWidth > 0 && holder.imgHeight > 0) {
                                float imgRatio = (float) holder.imgHeight / holder.imgWidth;
                                float viewRatio = (float) vh / vw;

                                // 如果是长图，触发适配计算
                                if (imgRatio > viewRatio * 1.2f) {
                                    holder.isLongImage = true;
                                    applyLongImageScale(holder);
                                }
                            }
                        });
                        return false;
                    }
                })
                .into(holder.photoView);

        if (item.type == MediaItem.MediaType.VIDEO) {
            holder.ivPlayIcon.setVisibility(View.VISIBLE);
            holder.ivPlayIcon.setOnClickListener(v -> {
                if (listener != null) listener.onVideoPlayClick(item);
            });
        } else {
            holder.ivPlayIcon.setVisibility(View.GONE);
        }

        // 单击触发全屏切换
        holder.photoView.setOnPhotoTapListener((view, x, y) -> {
            if (listener != null) {
                listener.onImageClick();
            }
        });
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
        holder.photoView.setMaximumScale(maxScale);
        holder.photoView.setMediumScale(midScale);
        holder.photoView.setMinimumScale(minScale);

        // 应用倍数，将锚点设在最顶端
        holder.photoView.setScale(targetSuppScale, 0f, 0f, false);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewerVH extends RecyclerView.ViewHolder {
        PhotoView photoView;
        ImageView ivPlayIcon;

        boolean isLongImage = false;
        int imgWidth = 0;
        int imgHeight = 0;

        ViewerVH(View itemView) {
            super(itemView);
            photoView = itemView.findViewById(R.id.photoView);
            ivPlayIcon = itemView.findViewById(R.id.ivPlayIcon);

            // 🚨 重绘哨兵：废弃一切矩阵还原黑魔法，直接同步重绘！
            photoView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (left == oldLeft && top == oldTop && right == oldRight && bottom == oldBottom) return;

                if (isLongImage && imgWidth > 0 && imgHeight > 0) {
                    // 注意这里没有用 photoView.post()！
                    // 我们在高度发生变化的同一帧里，同步算出新的比例盖上去，0 毫秒延迟，拒绝一切闪烁和贴边！
                    applyLongImageScale(this);
                }
            });
        }
    }
}