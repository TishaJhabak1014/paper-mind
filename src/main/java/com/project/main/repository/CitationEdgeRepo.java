package com.project.main.repository;

import com.project.main.entity.CitationEdge;
import com.project.main.entity.ResearchPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface CitationEdgeRepo extends JpaRepository<CitationEdge, Integer> {
    List<CitationEdge> findByFirstResearchPaper(ResearchPaper firstResearchPaper);
    
    List<CitationEdge> findBySecondResearchPaper(ResearchPaper secondResearchPaper);
    
    List<CitationEdge> findByFirstResearchPaperAndSecondResearchPaper(ResearchPaper firstResearchPaper, ResearchPaper secondResearchPaper);

    @Query("select count(edge) > 0 from CitationEdge edge where edge.graph.graphId = :graphId "
            + "and edge.firstResearchPaper.paperId = :firstPaperId "
            + "and edge.secondResearchPaper.paperId = :secondPaperId")
    boolean existsForGraphAndPapers(@Param("graphId") Integer graphId,
            @Param("firstPaperId") Integer firstPaperId,
            @Param("secondPaperId") Integer secondPaperId);

    @Query("select edge from CitationEdge edge where edge.graph.graphId = :graphId "
            + "and edge.firstResearchPaper.paperId = :firstPaperId "
            + "and edge.secondResearchPaper.paperId = :secondPaperId")
    Optional<CitationEdge> findForGraphAndPapers(@Param("graphId") Integer graphId,
            @Param("firstPaperId") Integer firstPaperId,
            @Param("secondPaperId") Integer secondPaperId);
}
