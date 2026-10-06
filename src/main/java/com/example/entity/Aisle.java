package com.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "aisle")
@Getter
@NoArgsConstructor
public class Aisle {

    @Id
    private Integer aisleId;

    @Column(name = "aisle")
    private String name;
}
