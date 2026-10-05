package com.sugar.refinery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sugar.refinery.entity.SugarBatch;

public interface SugarBatchRepository extends JpaRepository<SugarBatch, Long> {

    Optional<SugarBatch> findByBatchNo(String batchNo);

    List<SugarBatch> findByStatus(String status);

    List<SugarBatch> findByGrade(String grade);

    List<SugarBatch> findByWarehouse(String warehouse);
}
