/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.server;

import java.io.IOException;
import java.nio.channels.NetworkChannel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.MycatServer;
import io.mycat.config.ErrorCode;
import io.mycat.config.model.SchemaConfig;
import io.mycat.net.FrontendConnection;
import io.mycat.route.RouteResultset;
import io.mycat.server.handler.MysqlProcHandler;
import io.mycat.server.parser.ServerParse;
import io.mycat.server.response.Heartbeat;
import io.mycat.server.response.InformationSchemaProfiling;
import io.mycat.server.response.InformationSchemaProfilingSqlyog;
import io.mycat.server.response.Ping;
import io.mycat.server.util.SchemaUtil;
import io.mycat.util.SplitUtil;
import io.mycat.util.TimeUtil;

/**
 * @author mycat
 */
public class ServerConnection extends FrontendConnection {
	private static final Logger LOGGER = LoggerFactory
			.getLogger(ServerConnection.class);
	private static final long AUTH_TIMEOUT = 15 * 1000L;

	/** ??????SET SQL_SELECT_LIMIT??????, default ?????????-1. */
	private volatile  int sqlSelectLimit = -1;
	private volatile  boolean txReadonly;
	private volatile int txIsolation;
	private volatile boolean autocommit;
	private volatile boolean preAcStates; //?????????ac??????,?????????true
	private volatile boolean txInterrupted;
	private volatile String txInterrputMsg = "";
	private long lastInsertId;
	private NonBlockingSession session;
	/**
	 * ?????????????????????lock tables??????????????????lock??????
	 */
	private volatile boolean isLocked = false;
	
	public ServerConnection(NetworkChannel channel)
			throws IOException {
		super(channel);
		this.txInterrupted = false;
		this.autocommit = true;
		this.preAcStates = true;
		this.txReadonly = false;
	}

	@Override
	public boolean isIdleTimeout() {
		if (isAuthenticated) {
			return super.isIdleTimeout();
		} else {
			return TimeUtil.currentTimeMillis() > Math.max(lastWriteTime,
					lastReadTime) + AUTH_TIMEOUT;
		}
	}

	public int getTxIsolation() {
		return txIsolation;
	}

	public void setTxIsolation(int txIsolation) {
		this.txIsolation = txIsolation;
	}

	public boolean isAutocommit() {
		return autocommit;
	}

	public void setAutocommit(boolean autocommit) {
		this.autocommit = autocommit;
	}

	public boolean isTxReadonly() {
		return txReadonly;
	}

	public void setTxReadonly(boolean txReadonly) {
		this.txReadonly = txReadonly;
	}

	public int getSqlSelectLimit() {
		return sqlSelectLimit;
	}

	public void setSqlSelectLimit(int sqlSelectLimit) {
		this.sqlSelectLimit = sqlSelectLimit;
	}

	public long getLastInsertId() {
		return lastInsertId;
	}

	public void setLastInsertId(long lastInsertId) {
		this.lastInsertId = lastInsertId;
	}

	/**
	 * ????????????????????????????????????
	 */
	public void setTxInterrupt(String txInterrputMsg) {
		if (!autocommit && !txInterrupted) {
			txInterrupted = true;
			this.txInterrputMsg = txInterrputMsg;
		}
	}
	
	/**
	 * 
	 * ?????????????????????
	 * */
	public void clearTxInterrupt() {
		if (!autocommit && txInterrupted) {
			txInterrupted = false;
			this.txInterrputMsg = "";
		}
	}
	
	public boolean isTxInterrupted()
	{
		return txInterrupted;
	}
	public NonBlockingSession getSession2() {
		return session;
	}

	public void setSession2(NonBlockingSession session2) {
		this.session = session2;
	}
	
	public boolean isLocked() {
		return isLocked;
	}

	public void setLocked(boolean isLocked) {
		this.isLocked = isLocked;
	}

	@Override
	public void ping() {
		Ping.response(this);
	}

	@Override
	public void heartbeat(byte[] data) {
		Heartbeat.response(this, data);
	}

	public void execute(String sql, int type) {
		//??????????????????
		if (this.isClosed()) {
			LOGGER.warn("ignore execute ,server connection is closed " + this);
			return;
		}
		// ??????????????????
		if (txInterrupted) {
			writeErrMessage(ErrorCode.ER_YES,
					"Transaction error, need to rollback." + txInterrputMsg);
			return;
		}

		// ?????????????????????DB
		String db = this.schema;
		boolean isDefault = true;
		if (db == null) {
			db = SchemaUtil.detectDefaultDb(sql, type);
			if (db == null) {
				db = MycatServer.getInstance().getConfig().getUsers().get(user).getDefaultSchema();
				if (db == null) {
					writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
							"No MyCAT Database selected");
					return ;
				}
			}
			isDefault = false;
		}
		
		// ??????PhpAdmin's, ?????????MySQL????????????????????????
		//// TODO: 2016/5/20 ????????????information_schema??????
//		if (ServerParse.SELECT == type
//				&& db.equalsIgnoreCase("information_schema") ) {
//			MysqlInformationSchemaHandler.handle(sql, this);
//			return;
//		}

		if (ServerParse.SELECT == type 
				&& sql.contains("mysql") 
				&& sql.contains("proc")) {
			
			SchemaUtil.SchemaInfo schemaInfo = SchemaUtil.parseSchema(sql);
			if (schemaInfo != null 
					&& "mysql".equalsIgnoreCase(schemaInfo.schema)
					&& "proc".equalsIgnoreCase(schemaInfo.table)) {
				
				// ??????MySQLWorkbench
				MysqlProcHandler.handle(sql, this);
				return;
			}
		}
		
		SchemaConfig schema = MycatServer.getInstance().getConfig().getSchemas().get(db);
		if (schema == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
					"Unknown MyCAT Database '" + db + "'");
			return;
		}

		//fix navicat   SELECT STATE AS `State`, ROUND(SUM(DURATION),7) AS `Duration`, CONCAT(ROUND(SUM(DURATION)/*100,3), '%') AS `Percentage` FROM INFORMATION_SCHEMA.PROFILING WHERE QUERY_ID= GROUP BY STATE ORDER BY SEQ
		if(ServerParse.SELECT == type &&sql.contains(" INFORMATION_SCHEMA.PROFILING ")&&sql.contains("CONCAT(ROUND(SUM(DURATION)/"))
		{
			InformationSchemaProfiling.response(this);
			return;
		}

		//fix sqlyog select state, round(sum(duration),5) as `duration (summed) in sec` from information_schema.profiling where query_id = 0 group by state order by `duration (summed) in sec` desc
		if(ServerParse.SELECT == type &&sql.contains(" information_schema.profiling ")&&sql.contains("duration (summed) in sec"))
		{
			InformationSchemaProfilingSqlyog.response(this);
			return;
		}
		/* ?????????????????????schema?????????????????????sql???????????????schema???????????????
		 * ??????sql????????????mysql?????????????????????
		 * ???????????????????????????sql?????????Schema??????????????????
		 */
		if (isDefault && schema.isCheckSQLSchema() && isNormalSql(type)) {
			SchemaUtil.SchemaInfo schemaInfo = SchemaUtil.parseSchema(sql);
			if (schemaInfo != null && schemaInfo.schema != null && !schemaInfo.schema.equals(db)) {
				SchemaConfig schemaConfig = MycatServer.getInstance().getConfig().getSchemas().get(schemaInfo.schema);
				if (schemaConfig != null)
					schema = schemaConfig;
			}
		}

		routeEndExecuteSQL(sql, type, schema);

	}
	
	private boolean isNormalSql(int type) {
		return ServerParse.SELECT==type||ServerParse.INSERT==type||ServerParse.UPDATE==type||ServerParse.DELETE==type||ServerParse.DDL==type;
	}

    public RouteResultset routeSQL(String sql, int type) {

		// ?????????????????????DB
		String db = this.schema;
		if (db == null) {
			db = SchemaUtil.detectDefaultDb(sql, type);
			if (db == null){
				db = MycatServer.getInstance().getConfig().getUsers().get(user).getDefaultSchema();
				if (db == null) {
					writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
							"No MyCAT Database selected");
					return null;
				}
			}

		}
		SchemaConfig schema = MycatServer.getInstance().getConfig()
				.getSchemas().get(db);
		if (schema == null) {
			writeErrMessage(ErrorCode.ERR_BAD_LOGICDB,
					"Unknown MyCAT Database '" + db + "'");
			return null;
		}

		// ????????????
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, type, sql, this.charset, this);

		} catch (Exception e) {
			StringBuilder s = new StringBuilder();
			LOGGER.warn(s.append(this).append(sql).toString() + " err:" + e.toString(),e);
			String msg = e.getMessage();
			writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
			return null;
		}
		return rrs;
	}




	public void routeEndExecuteSQL(String sql, final int type, final SchemaConfig schema) {
		// ????????????
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, type, sql, this.charset, this);

		} catch (Exception e) {
			StringBuilder s = new StringBuilder();
			LOGGER.warn(s.append(this).append(sql).toString() + " err:" + e.toString(),e);
			String msg = e.getMessage();
			writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
			return;
		}
		if (rrs != null) {
			// session??????
			session.execute(rrs, rrs.isSelectForUpdate()?ServerParse.UPDATE:type);
		}
		
 	}

	/**
	 * ????????????
	 */
	public void commit() {
		if (txInterrupted) {
			LOGGER.warn("receive commit ,but found err message in Transaction {}",this);
			this.rollback();
//			writeErrMessage(ErrorCode.ER_YES,
//					"Transaction error, need to rollback.");
		} else {
			session.commit();
		}
	}

	/**
	 * ????????????
	 */
	public void rollback() {
		// ????????????
		if (txInterrupted) {
			txInterrupted = false;
		}

		// ????????????
		session.rollback();
	}
	/**
	 * ??????lock tables????????????
	 * @param sql
	 */
	public void lockTable(String sql) {
		// ????????????????????????lock table??????
		if (!autocommit) {
			writeErrMessage(ErrorCode.ER_YES, "can't lock table in transaction!");
			return;
		}
		// ???????????????lock table????????????unlock table?????????????????????????????????lock table??????
		if (isLocked) {
			writeErrMessage(ErrorCode.ER_YES, "can't lock multi-table");
			return;
		}
		RouteResultset rrs = routeSQL(sql, ServerParse.LOCK);
		if (rrs != null) {
			session.lockTable(rrs);
		}
	}
	
	/**
	 * ??????unlock tables????????????
	 * @param sql
	 */
	public void unLockTable(String sql) {
		sql = sql.replaceAll("\n", " ").replaceAll("\t", " ");
		String[] words = SplitUtil.split(sql, ' ', true);
		if (words.length==2 && ("table".equalsIgnoreCase(words[1]) || "tables".equalsIgnoreCase(words[1]))) {
			isLocked = false;
			session.unLockTable(sql);
		} else {
			writeErrMessage(ErrorCode.ER_UNKNOWN_COM_ERROR, "Unknown command");
		}
		
	}

	/**
	 * ????????????????????????
	 * 
	 * @param sponsor
	 *            ????????????null???????????????
	 */
	public void cancel(final FrontendConnection sponsor) {
		processor.getExecutor().execute(new Runnable() {
			@Override
			public void run() {
				session.cancel(sponsor);
			}
		});
	}

	@Override
	public void close(String reason) {
		super.close(reason);
		session.terminate();
		if(getLoadDataInfileHandler()!=null)
		{
			getLoadDataInfileHandler().clear();
		}
	}

	/**
	 * add huangyiming ??????????????????????????????????????????
	 * @param srcText
	 * @param findText
	 * @return
	 */
	public static int appearNumber(String srcText, String findText) {
	    int count = 0;
	    Pattern p = Pattern.compile(findText);
	    Matcher m = p.matcher(srcText);
	    while (m.find()) {
	        count++;
	    }
	    return count;
	}
	@Override
	public String toString() {
		
		return "ServerConnection [id=" + id + ", schema=" + schema + ", host="
				+ host + ", user=" + user + ",txIsolation=" + txIsolation
				+ ", autocommit=" + autocommit + ", schema=" + schema+ ", executeSql=" + executeSql + "]" +
				this.getSession2();
		
	}

	public boolean isPreAcStates() {
		return preAcStates;
	}

	public void setPreAcStates(boolean preAcStates) {
		this.preAcStates = preAcStates;
	}

}
