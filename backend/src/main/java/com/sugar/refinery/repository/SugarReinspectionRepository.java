package com.sugar.refinery.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sugar.refinery.entity.SugarReinspection;

public interface SugarReinspectionRepository extends JpaRepository<SugarReinspection, Long> {

    /** 幂等键查重：同一 requestId 只落一笔 */
    Optional<SugarReinspection> findByRequestId(String requestId);

    List<SugarReinspection> findByBatchIdOrderByVersionDesc(Long batchId);

    List<SugarReinspection> findByBatchIdInOrderByVersionDesc(Collection<Long> batchIds);

    /** 取批号内最新一版 */
    Optional<SugarReinspection> findFirstByBatchIdOrderByVersionDesc(Long batchId);

    @Query("select coalesce(max(r.version), 0) from SugarReinspection r where r.batchId = :batchId")
    int maxVersionByBatchId(@Param("batchId") Long batchId);
}
