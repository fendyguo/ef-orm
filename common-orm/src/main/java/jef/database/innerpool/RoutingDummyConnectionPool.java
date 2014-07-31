package jef.database.innerpool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import jef.common.Callback;
import jef.common.pool.PoolStatus;
import jef.database.ConnectInfo;
import jef.database.DbCfg;
import jef.database.DbMetaData;
import jef.database.DbUtils;
import jef.database.DebugUtil;
import jef.database.datasource.IRoutingDataSource;
import jef.database.dialect.DatabaseDialect;
import jef.database.meta.Feature;
import jef.database.wrapper.AbstractPopulator;
import jef.tools.JefConfiguration;

import com.google.common.collect.MapMaker;

/**
 * 启用了外部连接池后的路由伪连接池。
 * 实际上所有真正的连接都是从外部连接池中获取的。每次释放的时候这些连接都被关闭（即释放回外部连接池）
 * 
 * 
 * @author jiyi
 *
 */
class RoutingDummyConnectionPool extends AbstractPopulator implements IRoutingConnectionPool{
	protected IRoutingDataSource datasource;
	final Map<Object, ReentrantConnection> usedConnection=new MapMaker().concurrencyLevel(12).weakKeys().makeMap();
	private final AtomicInteger pollCount=new AtomicInteger();
	private final AtomicInteger offerCount=new AtomicInteger();
	private final Map<String,DbMetaData> metadatas=new HashMap<String,DbMetaData>(8,0.5f);
	
	RoutingDummyConnectionPool(IRoutingDataSource ds){
		this.datasource=ds;
	}
	
	public ReentrantConnection poll(Object transaction) throws SQLException {
		pollCount.incrementAndGet();
		ReentrantConnection conn=usedConnection.get(transaction);
		if(conn==null){
			conn=new RoutingConnection(this);
			conn.ensureOpen();//对于Routing连接来说，监测是否有效是没有意义的
			usedConnection.put(transaction, conn);
			conn.setUsedByObject(transaction);
		}else{
			conn.addUsedByObject();
		}
		return conn;
	}

	public ReentrantConnection poll() throws SQLException {
		return poll(Thread.currentThread());
	}
	
	public void offer(ReentrantConnection conn){
		offerCount.incrementAndGet();
		if(conn!=null){
			//处理内部的记录数据
			Object o=conn.popUsedByObject();
			if(o==null)return;//不是真正的归还
			IConnection conn1=usedConnection.remove(o);
			conn.closePhysical();
			if(conn1!=conn){
				throw  new IllegalStateException("The connection returned not match.");
			}
		}
	}
	
	public void close() throws SQLException {
		for(IConnection conn: usedConnection.values()){
			conn.closePhysical();	
		}
		usedConnection.clear();
		
		for(DbMetaData meta:metadatas.values()){
			DebugUtil.getHolder(meta).close();
		}
		PoolService.logPoolStatic(getClass().getSimpleName(),pollCount.get(), offerCount.get());
	}

	public DataSource getDatasource() {
		return datasource;
	}

	public PoolStatus getStatus() {
		int size=usedConnection.size();
		return new PoolStatus(0,0,size,size,0);
	}

	public Collection<String> getAllDatasourceNames() {
		return datasource.getDataSourceNames();
	}

	public IRoutingDataSource getRoutingDataSource() {
		return datasource;
	}

	/**
	 * 默认实现，从不缓存连接
	 * @throws SQLException 
	 */
	public Connection getCachedConnection(String ds) throws SQLException {
		return datasource.getDataSource(ds).getConnection();
	}

	/**
	 * 默认实现，从不缓存连接，连接直接关闭（使用外部连接池的场景）
	 */
	public void putback(String ds, Connection conn) {
		DbUtils.closeConnection(conn);
	}


	public void closeConnectionTillMin() {
		for(DbMetaData meta:metadatas.values()){
			DebugUtil.getHolder(meta).closeConnectionTillMin();
		}
		//无需任何功能
	}

	public DbMetaData getMetadata(String dbkey) {
		dbkey=wrapNullKey(dbkey);
		DbMetaData meta=metadatas.get(dbkey);
		if(meta!=null)return meta;
		meta=createMetadata(dbkey);
		return meta;
	}

	private String wrapNullKey(String dbkey) {
		if(dbkey!=null){
			return dbkey;
		}
		Entry<String,DataSource> e=datasource.getDefaultDatasource();
		if(e!=null){
			return e.getKey();
		}else{
			throw new IllegalArgumentException("No default datasource found in "+datasource+"!");
		}
	}
	
	private synchronized DbMetaData createMetadata(String key) {
		DbMetaData meta=metadatas.get(key);
		if(meta==null){
			DataSource ds=datasource.getDataSource(key);
			MetadataConnectionPool pool=new MetadataConnectionPool(key,ds,this);
			meta=new DbMetaData(pool,this,key);
			metadatas.put(key, meta);
		}
		return meta;
	}

	public DatabaseDialect getProfile(String dbkey) {
		return getMetadata(dbkey).getProfile();
	}

	public ConnectInfo getInfo(String dbkey) {
		return getMetadata(dbkey).getInfo();
	}

	//这个方法是给内部用的，认为key都是规范的。
	public boolean hasRemarkFeature(String dbkey) {
		if(JefConfiguration.getBoolean(DbCfg.DB_NO_REMARK_CONNECTION, false)){
			return false;
		}
		DatabaseDialect profile;
		DbMetaData metadata=metadatas.get(dbkey);
		if(metadata!=null){
			profile=metadata.getProfile();
		}else{
			DataSource ds=this.datasource.getDataSource(dbkey);
			ConnectInfo info=DbUtils.tryAnalyzeInfo(ds, false);
			if(info==null){
				Connection conn=null;
				try{
					conn=ds.getConnection();
					info=DbUtils.tryAnalyzeInfo(conn);
				}catch(SQLException e){
				}finally{
					DbUtils.closeConnection(conn);
				}
			}	
			profile=info.getProfile();
		}
		return profile.has(Feature.REMARK_META_FETCH);
	}

	public boolean isRouting() {
		return true;
	}

	

	public void registeDbInitCallback(Callback<String, SQLException> callback) {
		this.datasource.setCallback(callback);
	}

	public boolean isDummy() {
		return true;
	}
}