package se.seamless.ers.components.dataaggregator.aggregator

import groovy.util.logging.Log4j

import java.util.List

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled


@Log4j
@DynamicMixin
public class StdTopupDenominationsAggregator extends AbstractAggregator
{
	static final def TABLE = "topup_denominations"
	
	@Value('${StdTopupDenominationsAggregator.denominations:155,240,410,575,830,1250,2500,5000,7500,10000}')
	String denominations

	List denominationsList

	@Scheduled(cron = '${StdTopupDenominationsAggregator.cron:0 0/2 * * * ?}')
	public void aggregate()
	{
			log.info("StdTopupDenominationsAggregator started feeding topup denominations")
			if(denominations){
					denominationsList = denominations.split(",")
			}
		    updateAggregation(denominationsList)
	}


	private def updateAggregation(List aggregation)
	{
		if(aggregation)
		{
			def truncateSql = "TRUNCATE TABLE topup_denominations"
			jdbcTemplate.execute(truncateSql)
			
			def sql = "INSERT INTO ${TABLE} (denomination) values (?) ON DUPLICATE KEY UPDATE denomination = VALUES(denomination)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setInt(1, Integer.parseInt(aggregation[i]))
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
