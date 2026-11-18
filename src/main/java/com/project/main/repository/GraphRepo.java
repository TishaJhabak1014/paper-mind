package com.project.main.repository;

import com.project.main.entity.Graph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface GraphRepo extends JpaRepository<Graph, Integer> {
    @EntityGraph(attributePaths = {"appResponse.userPrompt"})
    Optional<Graph> findWithSearchContextByGraphId(Integer graphId);
}
