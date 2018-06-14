package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate

import java.sql.ResultSet
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import java.sql.Date

/**
 * Keeps the record of the last sold vouchers by voucher on demand feature.
 *
 * @author Danish Amjad
 */
@Log4j
@DynamicMixin
public class StdEVouchersStatusSummaryAggregator extends AbstractAggregator {
    private static final def TABLE = "std_e_vouchers_status_summary_aggregation"

    private def SELECT_ALL_VOUCHERS_WITH_GIVEN_STATUSES_WITHIN_N_DAYS = "SELECT vouchers_status_id, COUNT(*) AS vouchers_count, SUM(price_value) AS vouchers_amount, DATE(last_sell_date) AS sell_date " +
            "FROM dwa_vouchers WHERE vouchers_status_id IN (%s) AND expiry_date > NOW() AND DATE(last_sell_date) BETWEEN CURDATE() - INTERVAL %s DAY AND CURDATE() GROUP BY vouchers_status_id, sell_date " +
            "UNION ALL " +
            "SELECT 9 AS vouchers_status_id, COUNT(*) AS vouchers_count, SUM(price_value) AS vouchers_amount, DATE(last_sell_date) AS sell_date " +
            "FROM dwa_vouchers WHERE vouchers_status_id IN (3) AND expiry_date < NOW() AND DATE(last_sell_date) BETWEEN CURDATE() - INTERVAL %s DAY AND CURDATE() GROUP BY vouchers_status_id, sell_date "

    private def INSERT_AGGREGATED_DATA = "INSERT INTO std_e_vouchers_status_summary_aggregation (vouchers_status_id, vouchers_count, vouchers_amount, sell_date ) " +
            " VALUES (?, ?, ?, ?)"

    @Value('${StdEVouchersStatusSummaryAggregator.lastNDays:90}')
    int lastNumberOfDays

    @Autowired
    @Qualifier("vouchersDb")
    private JdbcTemplate vouchersDb;


    @Transactional
    @Scheduled(cron = '${StdEVouchersStatusSummaryAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {
        log.info("Started aggregation ...")
        int aggregationCount = 0;
        def vouchers = getVouchers()

        if (vouchers) {
            aggregationCount = vouchers?.size()
            updateAggregation(vouchers)

        }
        //schedule()
        log.info("Ended aggregation. ${aggregationCount} days are aggregated!")
    }

    private def getVouchers() {

        String sql = String.format(SELECT_ALL_VOUCHERS_WITH_GIVEN_STATUSES_WITHIN_N_DAYS,
                VOUCHER_STATUS.getAllValuesForSQLINStatement(), lastNumberOfDays, lastNumberOfDays)
        log.debug(log.isDebugEnabled() ? "Going to fetch vouchers data from vouchers DB with this query : " + sql : null)
        def resultSet = vouchersDb.query(sql, new ColumnMapRowMapper())

        log.debug(log.isDebugEnabled()? "Fetched  : "+ resultSet.size() : null)
        return resultSet
    }


    private def updateAggregation(def vouchers) {

        // truncate table - delete all data
        log.debug(log.isDebugEnabled()? "Going to Truncate the table : ${TABLE}":null)

        jdbcTemplate.update("truncate table ${TABLE}")
        log.debug(log.isDebugEnabled()? "Using sql query for Insert Vouchers data - " + INSERT_AGGREGATED_DATA :null)

        int index = 0;
        jdbcTemplate.batchUpdate(INSERT_AGGREGATED_DATA, [
                setValues   : { ps, i ->
                    index = 0;
                    ps.setLong(++index, vouchers[i].VOUCHERS_STATUS_ID)
                    ps.setBigDecimal(++index, vouchers[i].VOUCHERS_COUNT)
                    ps.setBigDecimal(++index, vouchers[i].VOUCHERS_AMOUNT)
                    ps.setDate(++index, vouchers[i].SELL_DATE)
                },
                getBatchSize: { vouchers.size() }
        ] as BatchPreparedStatementSetter)
    }

    private enum VOUCHER_STATUS {
        //EXPIRED is added for this report only.
        SOLD(3), REDEEMED(4), REVERSED(5), BLOCKED(6), EXPIRED(9)

        int statusId

        static String inStatement = ""

        public VOUCHER_STATUS(int statusId) {
            this.statusId = statusId;
        }

        public int getStatusId() {
            return statusId;
        }

        public void setStatusId(int statusId) {
            this.statusId = statusId;
        }

        public static String getAllValuesForSQLINStatement() {
            if (inStatement.length() == 0) {
                def list = VOUCHER_STATUS.values();
                list.each { status -> inStatement = inStatement + ", '" + status.getStatusId()+"'" }
                inStatement = inStatement.substring(2)
                return inStatement
            }
            return inStatement
        }
    }
}
