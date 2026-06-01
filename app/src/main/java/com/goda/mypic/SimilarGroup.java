package com.goda.mypic;

import java.util.ArrayList;
import java.util.List;

public class SimilarGroup {
    public long primaryHash;
    public List<MediaItem> similarItems = new ArrayList<>();
    public double averageSimilarity = 0.0;

    public SimilarGroup(MediaItem firstItem, long hash) {
        this.primaryHash = hash;
        this.similarItems.add(firstItem);
    }

    public SimilarGroup() {

    }
}