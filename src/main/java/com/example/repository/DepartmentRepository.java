package com.example.repository;

import com.example.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Integer> {

    List<Department> findAllByOrderByNameAsc();

    /** Exact match; the label came from this same table via the options endpoint. */
    @Query(value = "SELECT department_id FROM department WHERE department = :name", nativeQuery = true)
    Integer findIdByName(@Param("name") String name);
}
