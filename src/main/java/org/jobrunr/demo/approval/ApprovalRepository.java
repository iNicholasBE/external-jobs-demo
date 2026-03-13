package org.jobrunr.demo.approval;

import org.springframework.data.repository.CrudRepository;

public interface ApprovalRepository extends CrudRepository<ApprovalRequest, Long> {
}
