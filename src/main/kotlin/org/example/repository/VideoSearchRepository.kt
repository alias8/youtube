package org.example.repository

import org.example.model.VideoDocument
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.annotations.Query
import org.springframework.data.elasticsearch.core.SearchHits
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository

interface VideoSearchRepository : ElasticsearchRepository<VideoDocument, String> {

    // title^2 boosts title matches above description matches in relevance scoring.
    // fuzziness AUTO handles typos (e.g. "pythn" matches "python").
    @Query("""
        {
            "multi_match": {
                "query": "?0",
                "fields": ["title^2", "description"],
                "fuzziness": "AUTO"
            }
        }
    """)
    fun search(query: String, pageable: Pageable): SearchHits<VideoDocument>
}
