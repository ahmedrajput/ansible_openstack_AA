package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.Date
import java.util.List

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import se.seamless.ers.components.dataaggregator.txsource.Transaction



@Log4j
@DynamicMixin
public class StdDailyTopupSalesAggregator extends AbstractAggregator
{
	static final def TABLE = "std_daily_topup_sales_aggregation"
	static final def ALLOWED_PROFILES = ["TOPUP"]
	
	@Value('${StdDailyTopupSalesAggregator.denominations:155,240,410,575,830,1250,2500,5000,7500,10000}')
	String denominations

	@Value('${StdDailyTopupSalesAggregator.batch:1000}')
	int limit
	
	def denominationsList
	def denom

	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key>
	{
		Date date
		String denomination

		int compareTo(Key other)
		{
			date <=> other.date ?: denomination <=> other.denomination
		}
	}

	@Transactional
	@Scheduled(cron = '${StdDailyTopupSalesAggregator.cron:0 0/2 * * * ?}')
	public void aggregate()
	{
		log.info("StdDailyTopupSalesAggregator started feeding transactions")
		def txTransactions = getTransactions(limit)

		if (!txTransactions)
		{
			log.info("No transactions returned")
			return
		}

		if (txTransactions)
		{

			if(denominations){
					denominationsList = denominations.split(",")
			}
 		    def aggregation =findAllowedProfiles(txTransactions, ALLOWED_PROFILES)
                    .findAll(successfulTransactions)
                    .collect(transactionInfo)
                    .groupBy(key)
                    .collect(statistics)
		    updateAggregation(aggregation)
            updateCursor(txTransactions)
            schedule()
         }
	}
	
	private def transactionInfo =
            {
                log.debug("Processing transaction: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("TransactionData --- "+tr)

                def date = it.getStartTime().clone().clearTime()
                def denomination = new BigDecimal(tr.get("topupAmount")?.getAsJsonObject()?.get("value")?.getAsString().split("\\.")[0].toString())
                def denom = String.valueOf(denomination)
                
				def flexidenom = false
				if(!denominationsList.any{denom.contains(it)}){
					flexidenom = true
				}
				if(flexidenom){
					denom = 'Flexible'
				}
               
                [
                        date                  : date,
						denomination          : denomination,
						denom				  : denom
                ]
            }

    private def key =
            {
                log.debug("Creating key from ${it}")
                [
                        date                  : it.date,
						denomination          : it.denomination
					    
                ]
            }

    private def statistics =
            {
            	key, list ->
            	def totalAmount = 0
            	def denom
            	list.each
                        {
                            totalAmount += it.denomination
                            denom = it.denom
                        }
                [
                        key             : key,
                        totalAmount     : totalAmount,
                        denom			: denom
                ]
            }

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "INSERT INTO ${TABLE} (date, denomination, total_amount) values (?, ?, ?) ON DUPLICATE KEY UPDATE total_amount=total_amount+VALUES(total_amount)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].key.date))
					ps.setString(2, aggregation[i].denom)
					ps.setBigDecimal(3, aggregation[i].totalAmount)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
