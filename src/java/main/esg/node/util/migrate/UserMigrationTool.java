/***************************************************************************
*                                                                          *
*  Organization: Earth System Grid Federation                              *
*                                                                          *
****************************************************************************
*                                                                          *
*   Copyright (c) 2009, Lawrence Livermore National Security, LLC.         *
*   Produced at the Lawrence Livermore National Laboratory                 *
*   LLNL-CODE-420962                                                       *
*                                                                          *
*   All rights reserved. This file is part of the:                         *
*   Earth System Grid Federation (ESGF)                                    *
*   Data Node Software Stack, Version 1.0                                  *
*                                                                          *
*   For details, see http://esgf.org/esg-node-site/                        *
*   Please also read this link                                             *
*    http://esgf.org/LICENSE                                               *
*                                                                          *
*   * Redistribution and use in source and binary forms, with or           *
*   without modification, are permitted provided that the following        *
*   conditions are met:                                                    *
*                                                                          *
*   * Redistributions of source code must retain the above copyright       *
*   notice, this list of conditions and the disclaimer below.              *
*                                                                          *
*   * Redistributions in binary form must reproduce the above copyright    *
*   notice, this list of conditions and the disclaimer (as noted below)    *
*   in the documentation and/or other materials provided with the          *
*   distribution.                                                          *
*                                                                          *
*   Neither the name of the LLNS/LLNL nor the names of its contributors    *
*   may be used to endorse or promote products derived from this           *
*   software without specific prior written permission.                    *
*                                                                          *
*   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS    *
*   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT      *
*   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS      *
*   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL LAWRENCE    *
*   LIVERMORE NATIONAL SECURITY, LLC, THE U.S. DEPARTMENT OF ENERGY OR     *
*   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,           *
*   SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT       *
*   LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF       *
*   USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND    *
*   ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,     *
*   OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT     *
*   OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF     *
*   SUCH DAMAGE.                                                           *
*                                                                          *
***************************************************************************/
package esg.node.util.migrate;

import java.util.Properties;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.PoolingDataSource;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.DriverManagerConnectionFactory;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

import esg.node.security.*;
import esg.common.util.ESGFProperties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.*;

/**
   Description:
   A tool for migrating user accounts from the "gateway" to the "idp" node

*/
public final class UserMigrationTool {

    private static Log log = LogFactory.getLog(UserMigrationTool.class);
    
    //For source database access...
    private PoolingDataSource sourceDataSource = null;
    private GenericObjectPool connectionPool = null;
    private QueryRunner queryRunner = null;


    //For target (local) database access...
    private UserInfo userInfo = null;
    private UserInfoCredentialedDAO userDAO = null;
    private GroupRoleDAO groupRoleDAO = null;
    
    //-------------------------------------------------------
    //Remote "Gateway" queries
    //-------------------------------------------------------
    private static final String sourceUserInfoQuery = "select firstname, lastname, email, username, password, dn, organization, city, state, country from security.user where username!=''";
    private static final String sourceGroupInfoQuery = "select g.name as name, g.description as description, g.visible as visible, g.automatic_approval as automatic_approval from security.group as g, security.user as u, security.membership as m, security.role as r where u.username='rootAdmin' and u.id=m.user_id and m.group_id=g.id and m.role_id=r.id and r.name='admin'";
    private static final String sourceRoleInfoQuery = "select name, description from security.role";
    private static final String sourcePermissionInfoQuery = "select u.username as uname, g.name as gname, r.name as rname from security.user as u, security.group as g, security.role as r, security.membership as m, security.status as st where u.username not in ('', 'rootAdmin') and m.user_id=u.id and m.group_id=g.id and m.role_id=r.id and m.status_id=st.id and st.name='valid'";
    //-------------------------------------------------------
    
    public UserMigrationTool() { }
    
    //ToDo: should throw exception here
    public UserMigrationTool init(Properties props) {
        if(setupTargetResources()) setupSourceResources(props);
        return this;
    }

    //-------------------------------------------------------
    //Remote "Gateway" resouce setup...
    //-------------------------------------------------------
    public UserMigrationTool setupSourceResources(Properties props) {
        
        log.trace("Setting up source data source ");
        if(props == null) { log.error("Property object is ["+props+"]: Cannot setup up data source"); return this; }
        String user = props.getProperty("db.user","dbsuper");
        String password = props.getProperty("db.password");

        //Ex: jdbc:postgresql://pcmdi3.llnl.gov:5432/esgcet
        String database = props.getProperty("db.database","gateway-esg");
        String host = props.getProperty("db.host","localhost");
        String port = props.getProperty("db.port","5432"); //or perhaps 8080
        String protocol = props.getProperty("db.protocol","jdbc:postgresql:");

        return this.setupSourceResources(protocol,host,port,database,user,password);
                
    }
    
    public UserMigrationTool setupSourceResources(String protocol, 
                                                  String host,
                                                  String port,
                                                  String database,
                                                  String user,
                                                  String password) {

        System.out.println("Setting up source resources...");
        
        String connectURI = protocol+"//"+host+":"+port+"/"+database; //zoiks
        log.debug("Source Connection URI  = "+connectURI);
        log.debug("Source Connection User = "+user);
        log.debug("Source Connection Password = "+(null == password ? password : "********"));
        this.connectionPool = new GenericObjectPool(null);
        ConnectionFactory connectionFactory = new DriverManagerConnectionFactory(connectURI,user,password);
        PoolableConnectionFactory poolableConnectionFactory = new PoolableConnectionFactory(connectionFactory,connectionPool,null,null,false,true);
        this.sourceDataSource = new PoolingDataSource(connectionPool);
        this.queryRunner = new QueryRunner(sourceDataSource);
        return this;
    }

    //-------------------------------------------------------
    //Target (local) resource setup...
    //-------------------------------------------------------
    private boolean setupTargetResources() { return this.setupTargetResources(null,null); }

    private boolean setupTargetResources(UserInfoCredentialedDAO userDAO, GroupRoleDAO groupRoleDAO) {

        System.out.println("Setting up target (local) resources...");

        try{
            ESGFProperties env = new ESGFProperties();
            if(userDAO == null) {
                this.userDAO = new UserInfoCredentialedDAO("rootAdmin",
                                                           env.getAdminPassword(),
                                                           env);
            }else {
                this.userDAO = userDAO;
            }
            
            if(groupRoleDAO == null) {
                groupRoleDAO = new GroupRoleDAO(env);
            }else {
                this.groupRoleDAO = groupRoleDAO;
            }
        }catch(java.io.IOException e) { e.printStackTrace(); }
        
        return ((null != userDAO) && (null != groupRoleDAO));
    }
    
    public void shutdownSourceResources() {
        log.info("Shutting Down Source Database Resource...");
        try{
            connectionPool.close();
        }catch(Exception ex) {
            log.error("Problem with closing connection Pool!",ex);
        }
        sourceDataSource = null;
    }
    

    //-------------------------------------------------------
    //Pump the data from source --to-> target
    //-------------------------------------------------------

    public int migrate() {
        migrateRoles();
        migrateGroups();
        migrateUsers();
        migratePermissions();
        return 0;
    }
    
    public int migrateRoles() {
        int ret = 0;
        ResultSetHandler<Integer> rolesResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                while(rs.next()) {
                    //                                              [name]         [description] 
                    if(UserMigrationTool.this.groupRoleDAO.addRole(rs.getString(1),rs.getString(2))) {
                        i++;
                        log.info("Migrated role #"+i+": "+rs.getString(1));
                    }
                }
                return i;
            }
        };
        
        try {
            ret = queryRunner.query(sourceRoleInfoQuery, rolesResultSetHandler);
            log.info("Migrated ["+ret+"] role records");
        }catch(SQLException e) {
            e.printStackTrace();
        }
        
        return ret;
    }
    
    public int migrateGroups() {
        int ret = 0;
        ResultSetHandler<Integer> groupsResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                while(rs.next()) {
                    //                                               [name]         [description]    [visible]         [automatic_approval]
                    if(UserMigrationTool.this.groupRoleDAO.addGroup(rs.getString(1),rs.getString(2), rs.getBoolean(3), rs.getBoolean(4))) {
                        i++;
                        log.info("Migrated group #"+i+": "+rs.getString(1));
                    }
                }
                return i;
            }
        };
        
        try {
            ret = queryRunner.query(sourceGroupInfoQuery, groupsResultSetHandler);
            log.info("Migrated ["+ret+"] group records");
        }catch(SQLException e) {
            e.printStackTrace();
        }
        
        return ret;
    }

    public int migrateUsers() {
        int ret  = 0;
        ResultSetHandler<Integer> usersResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                while(rs.next()) {
                    UserInfo userInfo = UserMigrationTool.this.userDAO.getUserById(rs.getString(1));
                    userInfo.setFirstName(rs.getString("firstname")).
                        //setMiddleName(rs.getString("middlename")).
                        setLastName(rs.getString("lastname")).
                        setEmail(rs.getString("email")).
                        setUserName(rs.getString("username")).
                        setDn(rs.getString("dn")).
                        setOrganization(rs.getString("organization")).
                        //setOrgType(rs.getString("organization_type")).
                        setCity(rs.getString("city")).
                        setState(rs.getString("state")).
                        setCountry(rs.getString("country"));
                    //NOTE: verification token not applicable
                    //Status code msut be set separately... (below) field #13
                    //Password literal must be set separately... (see setPassword - with true boolean, below) field #14
                    
                    UserMigrationTool.this.userDAO.addUser(userInfo);
                    //UserMigrationTool.this.userDAO.setStatusCode(userInfo.getOpenid(),rs.getInt(13)); //statusCode
                    UserMigrationTool.this.userDAO.setPassword(userInfo.getOpenid(),rs.getString("password"),true); //password (literal)
                    i++;
                    log.info("Migrated User #"+i+": "+userInfo.getUserName()+" --> "+userInfo.getOpenid());
                }
                return i;
            }
        };
        
        try {
            ret = queryRunner.query(sourceUserInfoQuery, usersResultSetHandler);
            log.info("Migrated ["+ret+"] user records");
        }catch(SQLException e) {
            e.printStackTrace();
        }
        return ret;
        
    }
    
    public int migratePermissions() {
        int ret = 0;
        ResultSetHandler<Integer> permissionsResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                while(rs.next()) {
                    //                                                [uname]        [gname]        [rname]
                    if(UserMigrationTool.this.userDAO.addPermission(rs.getString(1),rs.getString(2),rs.getString(3))) {
                        i++;
                        log.info("Migrated Permission #"+i+": ["+rs.getString(1)+"] ["+rs.getString(2)+"] ["+rs.getString(3)+"]"); 
                    }
                }
                return i;
            }
        };
        
        try{
            ret = queryRunner.query(sourcePermissionInfoQuery, permissionsResultSetHandler);
            log.info("Migrated ["+ret+"] permission records");
        }catch(SQLException e) {
            e.printStackTrace();
        }
        return ret;
    }

    //-------------------------------------------------------
    //Main
    //-------------------------------------------------------
    public static void main(String[] args) {
        //Enter the connection URI information
        //setup source connection
        Properties props = new Properties();
        //manditory
        if(args.length >= 1) props.setProperty("db.user",args[0]);
        if(args.length >= 2) props.setProperty("db.password",args[1]);
        //have defaults
        if(args.length >= 3) props.setProperty("db.database",args[2]);
        if(args.length >= 4) props.setProperty("db.host",args[3]);
        if(args.length >= 5) props.setProperty("db.port",args[4]);
        if(args.length >= 6) props.setProperty("db.protocol",args[5]);

        (new UserMigrationTool()).init(props).migrate();
    }
    
}