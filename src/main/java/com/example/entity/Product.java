package com.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Scalar columns only. behavioral_emb, text_emb and name_tsv are deliberately not
 * mapped - Hibernate has no type for vector/tsvector and would fail validation.
 * Unmapped columns are ignored by ddl-auto=validate.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor
public class Product {

    @Id
    private Integer productId;

    private String product;

    private Integer aisleId;

    private Integer departmentId;

    @Column(name = "is_organic")
    private Short isOrganic;

    private Boolean inStock;
}
