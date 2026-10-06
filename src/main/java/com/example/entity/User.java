package com.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import com.example.dto.OnboardingPreferences;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId;

    private String name;

    private String mobile;


    private String password;


    @JdbcTypeCode(SqlTypes.JSON)
    private OnboardingPreferences preferences;


    @Column(insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private OffsetDateTime createdAt;

    public User(String name, String mobile, String password, OnboardingPreferences preferences) {
        this.name = name;
        this.mobile = mobile;
        this.password = password;
        this.preferences = preferences;
    }
}
