package se.seamless.ers.components.dataaggregator.aggregator

import groovy.swing.binding.JListProperties.*
import groovy.time.TimeCategory
import groovy.util.logging.Log4j

import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Script runs every day and copies stock levels to local table,
 * previous data is cleared. Data used to display stock levels in
 * some reports.
 * 
 * @author Saira Arif
 * 
 */
@Log4j
@DynamicMixin
public class StdStockReportAggregator extends AbstractAggregator {
	static final def dateFormat = "yyyy-MM-dd HH:mm:ss"
	
	def new_cursor_SQL="SELECT MAX(temp.last_modified) AS NEW_CURSOR FROM (SELECT last_modified FROM Refill.dwa_items WHERE last_modified > ? ORDER BY last_modified LIMIT ?) temp;"
	def OPERATOR_PRODUCT_SQL="""SELECT operator.SupplierKey AS operator_id,
								operator.Name As `operator_name`, 
								product.global_SKU As `product_SKU`, 
								product.name As `product_name`,
								CAST(product.status AS unsigned) AS status,
								product.last_updated
								FROM Refill.sup_suppliers operator 
								INNER JOIN dwa_products product ON operator.SupplierKey=product.supplier_key
								INNER JOIN dwa_evoucher_products evp ON product.product_key=evp.product_key """
	
	def SQL_DELETE = "DELETE FROM dataaggregator.std_stock_report_aggregation WHERE STATUS NOT IN (0,1,2)"
	def SQL_DELETE_EXPIRED = "DELETE FROM dataaggregator.std_stock_report_aggregation WHERE last_sell_date < NOW()"
	def SQL_CANCELLED_IMPORTS = "SELECT import_key FROM Refill.dwa_imports WHERE status = 2 "
	
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	@Value('${StdStockReportAggregator.batch:1000}')
	int limit
	
	@Transactional
	@Scheduled(cron = '${StdStockReportAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		def cursorDate = getDate()
		def newCursorDate = null;
		
		def cancelledImportsResult = refill.query(SQL_CANCELLED_IMPORTS, new ColumnMapRowMapper())
		if(cancelledImportsResult){
			log.info("Got stock cancelled imports : ${cancelledImportsResult.size()}")
			jdbcTemplate.batchUpdate("DELETE FROM dataaggregator.std_stock_report_aggregation WHERE import_key = ? ",  [
				setValues: { ps, i ->
					ps.setInt(1, cancelledImportsResult[i].import_key.toInteger())
				},
				getBatchSize: { cancelledImportsResult.size() }
			] as BatchPreparedStatementSetter)
				
		}
		
		def operatorProductResult = refill.query(OPERATOR_PRODUCT_SQL, new ColumnMapRowMapper())
		if(operatorProductResult){
			log.info("Got stock snapshot products : ${operatorProductResult.size()}")
			jdbcTemplate.batchUpdate("insert into dataaggregator.std_evoucher_products_aggregation (operator_id, operator_name, product_name, product_SKU, status, last_updated) values (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE product_name=VALUES(product_name), operator_name=VALUES(operator_name), status=VALUES(status), last_updated=VALUES(last_updated)",  [
				setValues: { ps, i ->
					ps.setInt(1, operatorProductResult[i].operator_id.toInteger())
					ps.setString(2, operatorProductResult[i].operator_name)
					ps.setString(3, operatorProductResult[i].product_name)
					ps.setString(4, operatorProductResult[i].product_SKU)
					ps.setInt(5, operatorProductResult[i].status.toInteger())
					ps.setTimestamp(6, operatorProductResult[i].last_updated)
				},
				getBatchSize: { operatorProductResult.size() }
			] as BatchPreparedStatementSetter)
		}
		
		def newCursorDateResult = refill.query(new_cursor_SQL,  [setValues: { ps ->
			use(TimeCategory) {
				ps.setString(1, cursorDate.format(dateFormat))
				ps.setInt(2, limit)
			}
		}] as PreparedStatementSetter, new ColumnMapRowMapper())
		if(newCursorDateResult)
		{
			newCursorDate = newCursorDateResult[0].NEW_CURSOR
			log.info("Got new cursor ${newCursorDateResult.size()} fetched and new cursor is : ${newCursorDate}")
		}
		log.info("Stock level cursor date : "+cursorDate +" and new cursor date : "+newCursorDate)
		// Group by operator.Name,product.name,product.global_SKU
		// Order by operator.Name,product.global_SKU
		
		def stock = null;
		if(newCursorDate){
			log.info("Fetching StdStockReportAggregator from refill for cursor : "+cursorDate.format(dateFormat))
			stock = refill.query("""
				SELECT operator.SupplierKey AS operator_id,
				operator.Name As `operator_name`, 
				product.global_SKU As `product_SKU`, 
				product.name As `product_name`, 
				CASE WHEN item.status in (1,2) THEN 1 ELSE 0 END As `available_stock`, 
				CASE WHEN item.status = 0 THEN 1 ELSE 0 END As `ready_to_activate`,
				item.serial As item_serial,item.status As item_status,
				item.last_modified As `item_last_modified`,
				CAST(item.import_key AS unsigned) AS import_key,
				item.last_sell_date
				FROM Refill.sup_suppliers operator 
				INNER JOIN dwa_products product ON operator.SupplierKey=product.supplier_key
				INNER JOIN dwa_evoucher_products evp ON product.product_key=evp.product_key
				INNER JOIN dwa_items item on (item.class_key=product.product_key) AND item.last_sell_date >= NOW() AND item.last_modified > ? AND item.last_modified <= ?
				ORDER BY item_last_modified ASC 
				""",[setValues: { ps ->
					use(TimeCategory) {
						ps.setString(1, cursorDate.format(dateFormat))
						ps.setString(2, newCursorDate.format(dateFormat))
					}
				}] as PreparedStatementSetter, new ColumnMapRowMapper())
			
			log.info("Got ${stock.size()} contracts from refill.")
		}
		if(stock) {
			log.info("Inserting snapshot stock levels...")
			jdbcTemplate.batchUpdate("insert into dataaggregator.std_stock_report_aggregation (operator_name, product_name, product_SKU, available_stock, ready_to_activate,serial,status,last_sell_date,operator_id,import_key) values (?, ?, ?, ?, ?, ?,?,?,?,?) ON DUPLICATE KEY UPDATE available_stock=VALUES(available_stock), ready_to_activate=VALUES(ready_to_activate), status=VALUES(status),last_sell_date=VALUES(last_sell_date)",  [
				setValues: { ps, i ->
					ps.setString(1, stock[i].operator_name)
					ps.setString(2, stock[i].product_name)
					ps.setString(3, stock[i].product_SKU)
					ps.setInt(4, stock[i].available_stock.toInteger())
					ps.setInt(5, stock[i].ready_to_activate.toInteger())
					ps.setString(6, stock[i].item_serial)
					ps.setInt(7, stock[i].item_status)
					ps.setTimestamp(8, stock[i].last_sell_date)
					ps.setInt(9, stock[i].operator_id.toInteger())
					ps.setInt(10, stock[i].import_key.toInteger())
					cursorDate = stock[i].item_last_modified
				},
				getBatchSize: { stock.size() }
			] as BatchPreparedStatementSetter)
		}
		jdbcTemplate.update(SQL_DELETE)
		jdbcTemplate.update(SQL_DELETE_EXPIRED)
		
		if(newCursorDate != null){
			updateCursor(newCursorDate.format(dateFormat))
			log.info("Updated StdStockReportAggregator cursor date to  : "+newCursorDate.format(dateFormat))
		}
	}
	private getDate = {
		def cursor = getCursor()
		def date = toSqlDate(new Date())
		if(cursor) {
			date = Date.parse(dateFormat, cursor);
			use(TimeCategory) {
				date = toSqlDate(date)
			}
		}
		else{
			use(TimeCategory) {
				date = toSqlDate(date - 90.days)
			}
		}
		return date
	}
}
