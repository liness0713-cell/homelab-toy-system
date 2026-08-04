package com.toysystem.search.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

/**
 * CQRS读模型：从 policy-events 投影出来的保单快照，只保留搜索/展示需要的字段。
 * 用 policyNo 当ES文档ID——每次收到事件（创建/更新/取消）都用最新payload整份覆盖，
 * 天然是幂等的upsert，不用区分eventType分别处理。
 */
@Data
@Document(indexName = "policies")
public class PolicyDocument {

    @Id
    private String id; // = policyNo

    @Field(type = FieldType.Keyword)
    private String policyNo;

    @Field(type = FieldType.Text)
    private String holderName;

    @Field(type = FieldType.Keyword)
    private String productType;

    @Field(type = FieldType.Double)
    private BigDecimal premium;

    @Field(type = FieldType.Keyword)
    private String status;
}
