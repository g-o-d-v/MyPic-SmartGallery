package com.goda.mypic;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    private final Context context;
    private final List<MediaItem> mediaList;

    public boolean isMultiSelectMode = false;
    public Set<MediaItem> selectedItems = new HashSet<>();
    private final OnMediaInteractionListener listener;

    public interface OnMediaInteractionListener {
        void onSingleClick(MediaItem item);
        void onSelectionChanged(int count);
        void onStartDragSelect(int position);
    }

    public MediaAdapter(Context context, List<MediaItem> mediaList, OnMediaInteractionListener listener) {
        this.context = context;
        this.mediaList = mediaList;
        this.listener = listener;
    }

    // 全选/取消全选逻辑
    public void selectAll(boolean select) {
        selectedItems.clear();
        if (select) {
            selectedItems.addAll(mediaList);
        }
        notifyDataSetChanged();
    }

    public void clearSelection() {
        isMultiSelectMode = false;
        selectedItems.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_media, parent, false);
        return new MediaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaItem item = mediaList.get(position);

        int width = context.getResources().getDisplayMetrics().widthPixels / 4;
        ViewGroup.LayoutParams layoutParams = holder.ivThumbnail.getLayoutParams();
        layoutParams.height = width;
        holder.ivThumbnail.setLayoutParams(layoutParams);

        Glide.with(context).load(item.uri).diskCacheStrategy(DiskCacheStrategy.ALL).into(holder.ivThumbnail);

        if (item.type == MediaItem.MediaType.GIF) {
            holder.tvTypeTag.setVisibility(View.VISIBLE);
            holder.tvTypeTag.setText("GIF");
        } else if (item.type == MediaItem.MediaType.VIDEO) {
            holder.tvTypeTag.setVisibility(View.VISIBLE);
            holder.tvTypeTag.setText("VIDEO");
        } else {
            holder.tvTypeTag.setVisibility(View.GONE);
        }

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

        holder.itemView.setOnClickListener(v -> {
            if (isMultiSelectMode) {
                if (selectedItems.contains(item)) selectedItems.remove(item);
                else selectedItems.add(item);
                notifyItemChanged(position);
                listener.onSelectionChanged(selectedItems.size());
            } else {
                listener.onSingleClick(item);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (!isMultiSelectMode) {
                isMultiSelectMode = true;
                selectedItems.add(item);
                notifyDataSetChanged();
            } else {
                if (!selectedItems.contains(item)) {
                    selectedItems.add(item);
                    notifyItemChanged(position);
                }
            }
            listener.onStartDragSelect(position);
            listener.onSelectionChanged(selectedItems.size());
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return mediaList.size();
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumbnail, ivCheck;
        TextView tvTypeTag;
        public MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.ivThumbnail);
            ivCheck = itemView.findViewById(R.id.ivCheck);
            tvTypeTag = itemView.findViewById(R.id.tvTypeTag);
        }
    }
}