package com.filesearch.service;

import com.filesearch.model.FileMetadata;
import com.filesearch.model.dto.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightField;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenSearchService {

    private final RestHighLevelClient client;

    @Value("${opensearch.index-name}")
    private String indexName;

    /**
     * 인덱스 초기화 - 존재하지 않으면 생성
     */
    @PostConstruct
    public void initIndex() {
        try {
            boolean exists = client.indices().exists(
                    new GetIndexRequest(indexName), RequestOptions.DEFAULT);

            if (!exists) {
                CreateIndexRequest createRequest = new CreateIndexRequest(indexName);
                createRequest.settings(Settings.builder()
                        .put("index.number_of_shards", 1)
                        .put("index.number_of_replicas", 0)
                );
                createRequest.mapping(getMapping(), XContentType.JSON);
                client.indices().create(createRequest, RequestOptions.DEFAULT);
                log.info("OpenSearch 인덱스 생성 완료: {}", indexName);
            }
        } catch (IOException e) {
            log.error("OpenSearch 인덱스 초기화 실패", e);
        }
    }

    /**
     * 문서를 OpenSearch에 인덱싱
     */
    public void indexDocument(FileMetadata fileMetadata, String content) {
        try {
            Map<String, Object> document = new HashMap<>();
            document.put("file_id", fileMetadata.getId());
            document.put("file_name", fileMetadata.getFileName());
            document.put("file_path", fileMetadata.getFilePath());
            document.put("file_extension", fileMetadata.getFileExtension());
            document.put("content", content);
            document.put("indexed_at", new Date());

            IndexRequest indexRequest = new IndexRequest(indexName)
                    .id(fileMetadata.getId().toString())
                    .source(document, XContentType.JSON);

            client.index(indexRequest, RequestOptions.DEFAULT);
            log.info("OpenSearch 인덱싱 완료: {}", fileMetadata.getFileName());
        } catch (IOException e) {
            log.error("OpenSearch 인덱싱 실패: {}", fileMetadata.getFileName(), e);
        }
    }

    /**
     * OpenSearch 통합 검색
     */
    public SearchResult search(String query, int page, int size) {
        long startTime = System.currentTimeMillis();

        try {
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(QueryBuilders.multiMatchQuery(query, "content", "file_name")
                    .fuzziness("AUTO"));
            sourceBuilder.from(page * size);
            sourceBuilder.size(size);

            // 하이라이팅 설정
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            highlightBuilder.field("content").preTags("<mark>").postTags("</mark>");
            highlightBuilder.fragmentSize(200);
            highlightBuilder.numOfFragments(3);
            sourceBuilder.highlighter(highlightBuilder);

            SearchRequest searchRequest = new SearchRequest(indexName);
            searchRequest.source(sourceBuilder);

            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
            long searchTimeMs = System.currentTimeMillis() - startTime;

            List<SearchResult.SearchHit> hits = new ArrayList<>();
            for (SearchHit hit : response.getHits().getHits()) {
                Map<String, Object> source = hit.getSourceAsMap();

                String snippet = "";
                Map<String, HighlightField> highlightFields = hit.getHighlightFields();
                if (highlightFields.containsKey("content")) {
                    snippet = Arrays.toString(highlightFields.get("content").getFragments());
                } else if (source.containsKey("content")) {
                    String content = source.get("content").toString();
                    snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                }

                hits.add(SearchResult.SearchHit.builder()
                        .fileId(Long.parseLong(source.getOrDefault("file_id", "0").toString()))
                        .fileName(source.getOrDefault("file_name", "").toString())
                        .filePath(source.getOrDefault("file_path", "").toString())
                        .fileExtension(source.getOrDefault("file_extension", "").toString())
                        .contentSnippet(snippet)
                        .score(hit.getScore())
                        .build());
            }

            return SearchResult.builder()
                    .query(query)
                    .totalHits(response.getHits().getTotalHits().value)
                    .page(page)
                    .size(size)
                    .searchTimeMs(searchTimeMs)
                    .hits(hits)
                    .build();

        } catch (IOException e) {
            log.error("OpenSearch 검색 실패", e);
            return SearchResult.builder()
                    .query(query)
                    .totalHits(0)
                    .page(page)
                    .size(size)
                    .searchTimeMs(System.currentTimeMillis() - startTime)
                    .hits(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 인덱스 매핑 정의
     */
    private String getMapping() {
        return """
                {
                    "properties": {
                        "file_id": { "type": "long" },
                        "file_name": { "type": "text", "analyzer": "standard" },
                        "file_path": { "type": "keyword" },
                        "file_extension": { "type": "keyword" },
                        "content": { "type": "text", "analyzer": "standard" },
                        "indexed_at": { "type": "date" }
                    }
                    
                    /*"properties": {
                    	"file_id": {
                    	  "type": "long"
                    	},
                    	"file_name": {
                    	  "type": "text",
                    	  "analyzer": "korean",
                    	  "fields": {
                    		"keyword": {
                    		  "type": "keyword"
                    		}
                    	  }
                    	},
                    	"file_path": {
                    	  "type": "keyword"
                    	},
                    	"file_extension": {
                    	  "type": "keyword"
                    	},
                    	"content": {
                    	  "type": "text",
                    	  "analyzer": "korean"
                    	},
                    	"indexed_at": {
                    	  "type": "date"
                    	}
                    }*/
                    
                }
                """;
    }
}