package com.flashguard.reconciliation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReconciliationIssueMapper {
    @Insert("""
        INSERT INTO reconciliation_issue(activity_id, issue_key, details)
        VALUES (#{activityId}, SHA2(#{details}, 256), #{details})
        ON DUPLICATE KEY UPDATE last_seen_at = CURRENT_TIMESTAMP(6), observations = observations + 1
        """)
    void insert(@Param("activityId") long activityId, @Param("details") String details);
}
