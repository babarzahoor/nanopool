/*
   Copyright 2008-2009 Christian Vest Hansen

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package net.nanopool;

import java.io.PrintWriter;
import java.sql.SQLException;
import javax.sql.ConnectionPoolDataSource;

abstract class PoolingDataSourceSupport extends AbstractDataSource {
  final PoolState state;
  
  PoolingDataSourceSupport(ConnectionPoolDataSource source, Settings settings) {
    Config config = settings.getConfig();
    Connector[] connectors = new Connector[config.poolSize];
    for (int i = 0; i < connectors.length; i++) {
      connectors[i] = new Connector(source, config.ttl, config.time);
    }
    state = new PoolState(source, config, connectors);
  }
  
  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return state.source.getLogWriter();
  }
  
  @Override
  public int getLoginTimeout() throws SQLException {
    return state.source.getLoginTimeout();
  }
  
  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    state.source.setLogWriter(out);
  }
  
  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    state.source.setLoginTimeout(seconds);
  }
}
