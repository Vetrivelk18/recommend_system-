package com.example.repository;

import com.example.entity.Aisle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AisleRepository extends JpaRepository<Aisle, Integer> {

    List<Aisle> findAllByOrderByNameAsc();

    /** Exact match; the label came from this same table via the options endpoint. */
    @Query(value = "SELECT aisle_id FROM aisle WHERE aisle = :name", nativeQuery = true)
    Integer findIdByName(@Param("name") String name);
}
