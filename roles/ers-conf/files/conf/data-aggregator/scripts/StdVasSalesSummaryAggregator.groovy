package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.jdbc.core.PreparedStatementSetter

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode


/**
 *
 * @author Samimul Alam
 */
@Log4j
@DynamicMixin
public class StdVasSalesSummaryAggregator extends AbstractAggregator {
    static final def TABLE = "std_vas_sales_summary_aggregation"


	def allowedProfiles = []
	
	@Value('${StdVasSalesSummaryAggregator.allowed_profiles:DATA_BUNDLE,COMBO_BUNDLE,CRBT,SMS_BUNDLE,IDD_BUNDLE,PRODUCT_RECHARGE}')
	String allowed_profiles

    @Value('${StdVasSalesSummaryAggregator.batch:1000}')
    int limit
    
    @Value('${StdVasSalesSummaryAggregator.unSuccessfulAllowed:false}')
    boolean unSuccessfulAllowed

    @EqualsAndHashCode
    @ToString
    private static class Key {
        String ersReference
        Date date
        String profile
        int resultCode
        String resultStatus
        String bundleAmount
        String resellerType
        String resellerName
        String region
        String district
        String town
        String resellerMSISDN
		String productGroupId
		String productName
		String resellerId
    }

    @Transactional
    @Scheduled(cron = '${StdVasSalesSummaryAggregator.cron:0 0/2 * * * ?}')
    public void aggregate() {
	    allowedProfiles = []
		
		if(allowed_profiles){
			def allowedProfileList = allowed_profiles.split(",")
			for (resellerProfile in allowedProfileList) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
        def transactions = getTransactions(limit)
        log.info("transactions : "+transactions)
        if (transactions) {
        	def aggregation = findAllowedProfiles(transactions, allowedProfiles)
                    .collect(transactionInfo)
                    .findAll(unSuccessfulAllowed ? {unSuccessfulAllowed}:successfulTransactions)
                    .groupBy(key)
                    .collect(statistics)
   
            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo =
            {
                log.debug("Processing transaction StdVasSalesSummaryAggregator: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("***************transaction StdVasSalesSummaryAggregator :" + tr + " **************")


                def  region = tr.get("senderPrincipal")?.getAsJsonArray("groupIds")?.get(0)?.getAsString();
                def  district = tr.get("senderPrincipal")?.getAsJsonArray("groupIds")?.get(1)?.getAsString();
                def  town = tr.get("senderPrincipal")?.getAsJsonArray("groupIds")?.get(2)?.getAsString();
                def resellerId = getSenderResellerId(tr)
                def resellerName=tr.get("senderPrincipal")?.getAsJsonObject()?.get("resellerName")?.getAsString();
                def resellerTypeId=tr.get("senderPrincipal")?.getAsJsonObject()?.get("resellerTypeId")?.getAsString();
                def resellerMSISDN = tr.get("senderPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString();
                def profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")
                def bundleAmount = getBigDecimalFieldFromAnyField(tr, "value", "receivedAmount", "topupAmount");
                def result= tr.get("resultCode")?.getAsInt();
				def productGroupId =  tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("groupId")?.getAsString() 
				def productName =  tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("productName")?.getAsString()
                log.debug("Reseller Id "+resellerId+"Reseller Name "+resellerName+" Reseller MSISDN "+resellerMSISDN+ " Transaction TYpe "+profile +" AMOUNT "+bundleAmount+
                        " Region "+region+" District "+district+" Town "+town +" Result Code "+result+ " product Group Id "+ productGroupId +" product name "+ productName);

                def resultStatus = ""
                if (it.resultCode == 0)
                {
                    log.debug("In the result Success ");
                    resultStatus = "Success"
                }
                else
                {
                    log.debug("In the result fail");
                    resultStatus = "Failure"
                }

                [
					date: it.getStartTime(), 
					profile: profile, 
					resultCode: it.resultCode, 
					resultStatus: resultStatus, 
					bundleAmount:bundleAmount ,
					resellerType:resellerTypeId,
					resellerMSISDN:resellerMSISDN,
					resellerId:resellerId,
					resellerName:resellerName,
					region:region,
					town:town,
					district:district,
					ersReference: it.ersReference,
					productGroupId:productGroupId,
					productName:productName
				 ]
            }
    private def transactionFind =
            {
                it.resultStatus = "Success"
            }

    private def key =
            {
                log.debug("Creating key from ${it}");
                new Key(date: it.date,
                        profile: it.profile, resultCode: it.resultCode, resultStatus: it.resultStatus,
                        bundleAmount: it.bundleAmount,resellerType:it.resellerType,resellerMSISDN:it.resellerMSISDN,resellerName:it.resellerName,
                        resellerId:it.resellerId,region:it.region,town:it.town,district:it.district,ersReference: it.ersReference,productGroupId:it.productGroupId,productName:it.productName)
            }

    private def statistics =
            {	key, list ->
                [key: key, total: list.size()]
            }

    private def updateAggregation(List aggregation)
    {
        log.info("Aggregated into StdVasSalesSummaryAggregator ${aggregation.size()} rows.")
        if(aggregation)
        {

            def date = new Date()
            use(TimeCategory)
                    {
                        date = toSqlTimestamp(date - 15.days)
                    }

            // first remove old data
            def delete = "delete from ${TABLE} where transactionDate < ?"
            jdbcTemplate.batchUpdate(delete, [
                    setValues: { ps, i ->
                        ps.setTimestamp(1, toSqlTimestamp(date))
                    },
                    getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)

            // then insert new data
            def sql = "INSERT INTO ${TABLE} (transactionDate,transactionReference,resellerMSISDN,resellerType,transactionType,resellerName,amount,resultStatus,area,district,town,productGroupId,productName,resellerId) VALUES (?, ?, ?,?,?,?,?,?,?,?,?,?,?,?)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            {	ps, i ->
                                def row = aggregation[i]

                                ps.setTimestamp(1, toSqlTimestamp(row.key.date))
                                ps.setString (2, row.key.ersReference)
                                ps.setString(3, row.key.resellerMSISDN)
                                ps.setString(4, row.key.resellerType)
                                ps.setString(5, row.key.profile)
                                ps.setString(6, row.key.resellerName)
                                ps.setString(7,  row.key.bundleAmount)
                                ps.setString(8, row.key.resultStatus)
                                ps.setString(9, row.key.region)
                                ps.setString(10, row.key.district)
                                ps.setString(11, row.key.town)
								ps.setString(12, row.key.productGroupId)
								ps.setString(13, row.key.productName)
								ps.setString(14, row.key.resellerId)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}