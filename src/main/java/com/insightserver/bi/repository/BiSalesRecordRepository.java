package com.insightserver.bi.repository;

import com.insightserver.bi.domain.BiSalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** 销售流水仓库：含统计聚合（对话式数据统计的数据来源） */
public interface BiSalesRecordRepository extends JpaRepository<BiSalesRecord, Long>, JpaSpecificationExecutor<BiSalesRecord> {

    boolean existsByChannelId(Long channelId);

    boolean existsByProductId(Long productId);

    boolean existsByRegionId(Long regionId);

    boolean existsByActivityId(Long activityId);

    /** 按渠道汇总 GMV / 销量 */
    @Query("select s.channelId, sum(s.quantity), sum(s.amount) from BiSalesRecord s " +
            "where s.statDate >= :from and s.statDate <= :to group by s.channelId")
    List<Object[]> sumByChannel(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按商品汇总 GMV / 销量 */
    @Query("select s.productId, sum(s.quantity), sum(s.amount) from BiSalesRecord s " +
            "where s.statDate >= :from and s.statDate <= :to group by s.productId")
    List<Object[]> sumByProduct(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 按区域汇总 GMV / 销量 */
    @Query("select s.regionId, sum(s.quantity), sum(s.amount) from BiSalesRecord s " +
            "where s.statDate >= :from and s.statDate <= :to group by s.regionId")
    List<Object[]> sumByRegion(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 区间总额（GMV / 销量），sum 为 null 时视为 0 */
    @Query("select coalesce(sum(s.quantity), 0), coalesce(sum(s.amount), 0) from BiSalesRecord s " +
            "where s.statDate >= :from and s.statDate <= :to")
    List<Object[]> sumTotal(@Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByStatDateBetween(LocalDate from, LocalDate to);

    /** 按日期区间取明细：对话式统计在应用层做多条件过滤与分组，便于统一口径与复算 */
    List<BiSalesRecord> findByStatDateBetween(LocalDate from, LocalDate to);
}
