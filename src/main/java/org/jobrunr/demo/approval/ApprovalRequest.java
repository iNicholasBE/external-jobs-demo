package org.jobrunr.demo.approval;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("approval_request")
public class ApprovalRequest {

    @Id
    private Long id;
    private String jobKey;
    private String productName;
    private String content;
    private String aiRecommendation;
    private double aiConfidence;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobKey() { return jobKey; }
    public void setJobKey(String jobKey) { this.jobKey = jobKey; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAiRecommendation() { return aiRecommendation; }
    public void setAiRecommendation(String aiRecommendation) { this.aiRecommendation = aiRecommendation; }

    public double getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(double aiConfidence) { this.aiConfidence = aiConfidence; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public int getConfidencePercent() { return (int) (aiConfidence * 100); }
}
