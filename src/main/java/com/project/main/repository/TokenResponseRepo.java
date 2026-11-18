package com.project.main.repository;

import com.project.main.entity.TokenResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TokenResponseRepo extends JpaRepository<TokenResponse, Integer> {
    Optional<TokenResponse> findByUserPrompt_Id(Integer userPromptId);
}
