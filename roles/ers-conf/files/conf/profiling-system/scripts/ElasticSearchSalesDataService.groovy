package se.seamless.mcs.profilingsystem.services.impl

import etm.core.configuration.EtmManager
import etm.core.monitor.EtmPoint
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.aggregations.AggregationBuilder
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.bucket.filter.InternalFilter
import org.elasticsearch.search.aggregations.metrics.avg.Avg
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentiles
import org.elasticsearch.search.aggregations.metrics.sum.Sum
import org.elasticsearch.search.aggregations.metrics.valuecount.ValueCount
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Service
import se.seamless.mcs.profilingsystem.conf.ElasticSearchConfiguration
import se.seamless.mcs.profilingsystem.services.impl.AbstractSalesDataService

import java.util.concurrent.ConcurrentHashMap
import static se.seamless.mcs.profilingsystem.util.ProfilingSystemConstants.*;

/**
 *
 * Elasticsearch client. Query filters (SQL: where clause) and aggregations (SQL: select clause) are built here and
 * then query is sent to elasticsearch.
 * Created by danish.amjad on 1/25/17.
 */
@Service("ElasticSearchSalesDataService")
public class ElasticSearchSalesDataService extends AbstractSalesDataService {

    @Autowired
    private TransportClient transportClient;

    @Autowired
    private ElasticSearchConfiguration elasticSearchConfiguration;

    private Logger logger = LogManager.getLogger(getClass());

    public Map<String, Object> findCreditScoreMetrics(Map<String, Object> dynamicParams) {

        EtmPoint point = EtmManager.getEtmMonitor().createPoint("ElasticSearchSalesDataService::findCreditScoreMetrics");

        Map<String, Object> aggregatedMetrics = new ConcurrentHashMap<>(); ;

        SearchRequestBuilder searchRequestBuilder;
        QueryBuilder queryBuilder;


        SearchResponse searchResponse;

        try {
            searchRequestBuilder = initializeSearchRequestBuilder();
            queryBuilder = getQueryWithFilters(dynamicParams);
            searchRequestBuilder.setQuery(queryBuilder);

            searchRequestBuilder = setAggregationBuildersForSearch(searchRequestBuilder, dynamicParams);

            logger.debug("Querying elasticsearch with the searchRequest :" + searchRequestBuilder);
            searchResponse = searchRequestBuilder.execute().actionGet();
            logger.debug("Got response from elasticsearch : " + searchResponse);

            processResponse(searchResponse, aggregatedMetrics);

            if (((Double)aggregatedMetrics.get(SYSTEM_TRANSACTION_COUNT_KEY)) <= 0) {
                logger.debug("No transaction found in the system in the given date range.")
            }

        }
        catch (Exception ex) {
            logger.error("An exception has occurred while querying elasticsearch - ", ex);
            throw new Exception(ex);
        }
        finally {
            point?.collect();
        }
        return aggregatedMetrics;
    }

    private SearchRequestBuilder initializeSearchRequestBuilder() {
        return transportClient.prepareSearch(
                elasticSearchConfiguration.getIndexName()) //index name : db name
                .setTypes(elasticSearchConfiguration.getIndexTypeName()) //type name : table name
                .setSize(0) // return no response - only aggregations
    }

    private static QueryBuilder getQueryWithFilters(Map<String, Object> dynamicParams) {
        DateTime fromDate;
        DateTime toDate;

        fromDate = (DateTime) dynamicParams.get(FROM_DATE_KEY);
        toDate = (DateTime) dynamicParams.get(TO_DATE_KEY);
        return QueryBuilders.boolQuery()
                .filter(QueryBuilders.rangeQuery("endTime").gte(fromDate).lte(toDate));

    }

    private static SearchRequestBuilder setAggregationBuildersForSearch(SearchRequestBuilder searchRequestBuilder, Map<String,Object> dynamicParams) {
        AggregationBuilder averageSaleAggregationBuilder;
        AggregationBuilder topUpCountAggregationBuilder;
        //AggregationBuilder medianSaleAggregationBuilder;
        AggregationBuilder uniqueResellerCountAggregationBuilder;
        AggregationBuilder resellerSaleSumAggregationBuilder;
        String customerMSISDN;

        customerMSISDN = dynamicParams.get(CUSTOMER_MSISDN_KEY);
        averageSaleAggregationBuilder = AggregationBuilders.avg(SYSTEM_AVERAGE_SALE_KEY).field("requestAmountValue");
        topUpCountAggregationBuilder = AggregationBuilders.count(SYSTEM_TOPUP_COUNT_KEY).field("requestAmountValue");
        //medianSaleAggregationBuilder = AggregationBuilders.percentiles(SYSTEM_MEDIAN_SALE_KEY).percentiles(50).field("requestAmountValue");
        uniqueResellerCountAggregationBuilder = AggregationBuilders.cardinality(UNIQUE_RESELLERS_COUNT_KEY).field("senderResellerId");
        resellerSaleSumAggregationBuilder = AggregationBuilders.filter(RESELLER_SALE_SUM_KEY, QueryBuilders.termQuery("senderMSISDN", customerMSISDN)).subAggregation(AggregationBuilders.sum(RESELLER_SALE_SUM_KEY).field("requestAmountValue"));

        return searchRequestBuilder.addAggregation(averageSaleAggregationBuilder)
                .addAggregation(topUpCountAggregationBuilder)
                //.addAggregation(medianSaleAggregationBuilder)
                .addAggregation(uniqueResellerCountAggregationBuilder)
                .addAggregation(resellerSaleSumAggregationBuilder);
    }

    private static void processResponse(SearchResponse searchResponse, Map<String, Object> aggregatedMetrics) {

        Map aggregationMap;
        Avg averageSaleAggregation;
        ValueCount valueCountAggregation;
        Percentiles percentilesAggregation;
        Cardinality uniqueResellerCountAggregation;
        Sum resellerSalesSum;
        Double transactionCount;

        aggregationMap = searchResponse.getAggregations().asMap();

        averageSaleAggregation = (Avg) aggregationMap.get(SYSTEM_AVERAGE_SALE_KEY);
        valueCountAggregation = (ValueCount) aggregationMap.get(SYSTEM_TOPUP_COUNT_KEY);
        //percentilesAggregation = (Percentiles) aggregationMap.get(SYSTEM_MEDIAN_SALE_KEY);
        uniqueResellerCountAggregation = (Cardinality) aggregationMap.get(UNIQUE_RESELLERS_COUNT_KEY);
        transactionCount = ((Long) searchResponse.getHits().totalHits()).doubleValue();
        resellerSalesSum = ((InternalFilter) aggregationMap.get(RESELLER_SALE_SUM_KEY)).getAggregations().get(RESELLER_SALE_SUM_KEY);

        aggregatedMetrics.put(SYSTEM_AVERAGE_SALE_KEY, averageSaleAggregation.getValue());
        aggregatedMetrics.put(SYSTEM_TOPUP_COUNT_KEY, ((Long) valueCountAggregation.getValue()).doubleValue());
        //aggregatedMetrics.put(SYSTEM_MEDIAN_SALE_KEY, percentilesAggregation.percentile(50));
        aggregatedMetrics.put(UNIQUE_RESELLERS_COUNT_KEY, (Double) uniqueResellerCountAggregation.getValue());
        aggregatedMetrics.put(SYSTEM_TRANSACTION_COUNT_KEY, transactionCount);
        aggregatedMetrics.put(RESELLER_SALE_SUM_KEY, resellerSalesSum.getValue())

    }
}