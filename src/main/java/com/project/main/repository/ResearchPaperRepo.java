package com.project.main.repository;

import com.project.main.entity.ResearchPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface ResearchPaperRepo extends JpaRepository<ResearchPaper, Integer> {
    Optional<ResearchPaper> findBySemanticPaperId(String semanticPaperId);

    boolean existsBySemanticPaperId(String semanticPaperId);

    @Query("select paper.paperId from ResearchPaper paper where paper.semanticPaperId = :semanticPaperId order by paper.paperId")
    List<Integer> findIdsBySemanticPaperId(@Param("semanticPaperId") String semanticPaperId);

    @EntityGraph(attributePaths = "authors")
    List<ResearchPaper> findAllWithAuthorsBySemanticPaperIdIn(Collection<String> semanticPaperIds);
}
