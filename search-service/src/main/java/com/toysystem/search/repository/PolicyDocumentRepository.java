package com.toysystem.search.repository;

import com.toysystem.search.document.PolicyDocument;
import org.springframework.data.elasticsearch.annotations.Query;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface PolicyDocumentRepository extends ElasticsearchRepository<PolicyDocument, String> {

    // holderName做模糊/纠错匹配，policyNo/productType做精确匹配，一个输入框覆盖几种常见搜索方式
    @Query("""
            {
              "bool": {
                "should": [
                  { "match": { "holderName": { "query": "?0", "fuzziness": "AUTO" } } },
                  { "term": { "policyNo": "?0" } },
                  { "term": { "productType": "?0" } }
                ]
              }
            }
            """)
    List<PolicyDocument> search(String query);
}
