package com.aloute.dto.response;

/**
 * Một mục trong danh sách "Xu hướng" của trang tìm kiếm.
 *
 * topic       — tiêu đề hiển thị (hashtag "#abc" hoặc trích đoạn bài viết nổi bật)
 * description — mô tả ngắn, lấy từ bài viết tiêu biểu của chủ đề
 * meta        — dòng thống kê đã format sẵn ("12 bài viết", "34 lượt thích")
 * query       — từ khóa sẽ được điền vào ô tìm kiếm khi người dùng bấm vào
 */
public class TrendingTopicDto {

    private String topic;
    private String description;
    private String meta;
    private String imageUrl;
    private String query;
    private long postCount;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public long getPostCount() {
        return postCount;
    }

    public void setPostCount(long postCount) {
        this.postCount = postCount;
    }
}
