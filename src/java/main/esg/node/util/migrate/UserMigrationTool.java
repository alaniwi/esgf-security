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

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.regex.Matcher;

import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.DriverManagerConnectionFactory;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.PoolingDataSource;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool.impl.GenericObjectPool;

import esg.common.util.ESGFProperties;
import esg.node.security.ESGFDataAccessException;
import esg.node.security.GroupRoleDAO;
import esg.node.security.UserInfo;
import esg.node.security.UserInfoCredentialedDAO;
import esg.node.security.UserInfoDAO;

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

    //hostname of source machine
    private String source = null;

    //hostname of "this" target machine. (remember this migration is a *pull* operation)
    private String myHost = null;

    //-------------------------------------------------------
    //Remote "Gateway" queries
    //-------------------------------------------------------
    private static final String sourceUserInfoQuery = "select firstname, lastname, email, username, password, dn, organization, city, state, country, openid from security.user";
    private static final String sourceGroupInfoQuery = "select g.name as name, g.description as description, g.visible as visible, g.automatic_approval as automatic_approval from security.group as g";
    private static final String sourceRoleInfoQuery = "select name, description from security.role";
    private static final String sourcePermissionInfoQuery = "select u.openid as oid, g.name as gname, r.name as rname from security.user as u, security.group as g, security.role as r, security.membership as m, security.status as st where m.user_id=u.id and m.group_id=g.id and m.role_id=r.id and m.status_id=st.id and st.name='valid' and u.openid like 'http%'";
    //-------------------------------------------------------

    private boolean verbatimMigration = false;

    public UserMigrationTool() { }

    //ToDo: should throw exception here
    public UserMigrationTool init(Properties props) {
        log.trace("props = "+props);
        if(setupTargetResources()) setupSourceResources(props);
        try{
            java.util.Properties esgfProperties = new java.util.Properties();
            esgfProperties.load(new java.io.BufferedReader(new java.io.FileReader(System.getenv().get("ESGF_HOME")+"/config/esgf.properties")));
            myHost = esgfProperties.getProperty("esgf.host");
            myHost.trim(); //pretty much calling this to force an NPE if property is not found... so I can stop here (die early).
            verbatimMigration = Boolean.valueOf(System.getProperty("verbatim_migration", System.getenv().get("verbatim_migration")));
        }catch(Exception e) { log.error(e); }
        return this;
    }

    //-------------------------------------------------------
    //Remote "Gateway" resouce setup...
    //-------------------------------------------------------
    public UserMigrationTool setupSourceResources(Properties props) {

        log.info("Setting up source data source ");
        if(props == null) { log.error("Property object is ["+props+"]: Cannot setup up data source"); return this; }
        String user = props.getProperty("db.user","dbsuper");
        String password = props.getProperty("db.password");

        //Ex: jdbc:postgresql://pcmdi3.llnl.gov:5432/esgcet
        String database = props.getProperty("db.database","gateway-esg");
        String host = props.getProperty("db.host","localhost");
        String port = props.getProperty("db.port","5432"); //or perhaps 8080
        String protocol = props.getProperty("db.protocol","jdbc:postgresql:");

        this.source = host;
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

    private boolean setupTargetResources(UserInfoCredentialedDAO userDAO_, GroupRoleDAO groupRoleDAO_) {

        System.out.println("Setting up target (local) resources...");

        try{
            ESGFProperties env = new ESGFProperties();
            if(userDAO_ == null) {
                log.trace("need to instantiate user data object...");
                this.userDAO = new UserInfoCredentialedDAO("rootAdmin",
                                                           env.getAdminPassword(),
                                                           env);
            }else {
                this.userDAO = userDAO;
            }
            log.trace("userDAO = "+(userDAO == null ? "[NULL]" : "[OK]"));

            if(groupRoleDAO_ == null) {
                log.trace("need to instantiate group/role data object...");
                this.groupRoleDAO = new GroupRoleDAO(env);
            }else {
                log.trace("re-using previously instantiated group/role data object");
                this.groupRoleDAO = groupRoleDAO_;
            }
            log.trace("group/role = "+(groupRoleDAO == null ? "[NULL]" : "[OK]"));

        }catch(java.io.IOException e) { e.printStackTrace(); }

        return ((null != this.userDAO) && (null != this.groupRoleDAO));
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
                String roleName=null;
                while(rs.next()) {
                    roleName = transformRoleName(rs.getString(1));
                    //                                              [name]         [description]
                    if(UserMigrationTool.this.groupRoleDAO.addRole(roleName,rs.getString(2))) {
                        i++;
                        System.out.println("Migrated role #"+i+": "+roleName);
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
                String groupName = transformGroupName(rs.getString(1));
                while(rs.next()) {
                    //                                               [name]         [description]    [visible]         [automatic_approval]
                    if(UserMigrationTool.this.groupRoleDAO.addGroup(groupName,rs.getString(2), rs.getBoolean(3), rs.getBoolean(4))) {
                        i++;
                        System.out.println("Migrated group #"+i+": "+groupName);
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

    //Simple helper function to transform strings.  In this case we
    //use it to turn User to user, which is more amenable to the P2P
    //group naming convention. Clearly, this can become as ellaborate
    //as we may wish...
    //User -> user
    //default -> user
    private String transformGroupName(String in) {
        String out = null;
        if(in.equals("User")) { out = in.toLowerCase(); }
        else if(in.equals("default")) { out = "user"; }
        else{ out = in; }
        return out;
    }

    private String transformRoleName(String in) {
        String out = null;
        if(in.equals("User")) { out = in.toLowerCase(); }
        else if(in.equals("default")) { out = "user"; }
        else{ out = in; }
        return out;
    }

    public int migrateUsers() {
        int ret  = 0;
        ResultSetHandler<Integer> usersResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                int migrateCount=0;
                int transCount=0;
                int errorCount=0;
                String currentUsername=null;
                String openid=null;
                String target=null;
                while(rs.next()) {
                    try{
                        currentUsername = rs.getString("username");
                        if(currentUsername != null && currentUsername.equals("rootAdmin")) {
                            System.out.println("NOTE: Will not overwrite local rootAdmin information");
                            continue;
                        }
                        openid = rs.getString("openid");
                        log.trace("Inspecting openid: "+openid);
                        UserInfo userInfo = UserMigrationTool.this.userDAO.getUserById(openid);
                        userInfo.setFirstName(rs.getString("firstname")).
                            //setMiddleName(rs.getString("middlename")).
                            setLastName(rs.getString("lastname")).
                            setEmail(rs.getString("email")).
                            setDn(rs.getString("dn")).
                            setOrganization(rs.getString("organization")).
                            //setOrgType(rs.getString("organization_type")).
                            setCity(rs.getString("city")).
                            setState(rs.getString("state")).
                            setCountry(rs.getString("country"));
                        //NOTE: verification token not applicable
                        //Status code msut be set separately... (below) field #13
                        //Password literal must be set separately... (see setPassword - with true boolean, below) field #14

                        if(currentUsername != null) userInfo.setUserName(currentUsername);
                        
                        if(userInfo.getOpenid().matches("http.*"+UserMigrationTool.this.source+".*")) {
                            if(verbatimMigration) {
                                UserMigrationTool.this.userDAO.addUser(userInfo);
                                UserMigrationTool.this.userDAO.setPassword(userInfo.getOpenid(),rs.getString("password"),true); //password (literal)
                                System.out.println("**Migrated User #"+i+": "+userInfo.getUserName()+" ("+openid+") --> "+userInfo.getOpenid());
                            }
                            userInfo.setOpenid(null); //This will cause the DAO to generate a local Openid
                            UserMigrationTool.this.userDAO.addUser(userInfo);
                            //UserMigrationTool.this.userDAO.setStatusCode(userInfo.getOpenid(),rs.getInt(13)); //statusCode
                            UserMigrationTool.this.userDAO.setPassword(userInfo.getOpenid(),rs.getString("password"),true); //password (literal)
                            System.out.println("Migrated User #"+i+": "+userInfo.getUserName()+" ("+openid+") --> "+userInfo.getOpenid());
                            migrateCount++;
                        }else{
                            UserMigrationTool.this.userDAO.addUser(userInfo);
                            System.out.println("Transferred User #"+i+": "+userInfo.getUserName()+" --> "+userInfo.getOpenid());
                            transCount++;
                        }
                        i++;
                    }catch(Throwable t) {
                        log.error("Sorry, could NOT migrate/transfer user: "+currentUsername);
                        errorCount++;
                    }
                }
                log.info("Inspected "+i+" User Records: Migrated "+migrateCount+", Transferred "+transCount+", with "+errorCount+" failed");
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

    private String transformOpenid(String sourceOpenid) {

        String openid = sourceOpenid;
        String username = null;

        if (sourceOpenid.matches("http.*"+this.source+".*")) {
            //Discern if they user put in a an openid url or just a username, 
            //set values accordingly...
            Matcher openidMatcher = UserInfoDAO.openidUrlPattern.matcher(openid);
            String openidHost = null;
            String openidPort = null;
            String openidPath = null;
            if(openidMatcher.find()) {
                openidHost = openidMatcher.group(1);
                openidPort = openidMatcher.group(2);
                openidPath = openidMatcher.group(3);
                username = openidMatcher.group(4);

                log.trace("submitted openid = "+openid);
                log.trace("openidHost = "+openidHost);
                log.trace("openidPort = "+openidPort);
                log.trace("openidPath = "+openidPath);
                log.trace("username   = "+username);

                //reconstruct and transform the url scrubbing out port if necessary...
                openid = "https://"+myHost+"/esgf-idp/openid/"+username;
                log.trace("transformed oid: "+sourceOpenid+" -> "+openid);
                return openid;
            }else{
                log.warn("Could not transform openid: "+sourceOpenid);
            }
        }
        return openid;
    }

    public int migratePermissions() {
        int ret = 0;
        ResultSetHandler<Integer> permissionsResultSetHandler = new ResultSetHandler<Integer>() {
            public Integer handle(ResultSet rs) throws SQLException{
                int i=0;
                String oid=null;
                String gname=null;
                String rname=null;
                int errorCount=0;
                int migrateCount=0;
                while(rs.next()) {
                    try{
                        if(verbatimMigration) {
                            oid=rs.getString(1);
                            gname=transformGroupName(rs.getString(2));
                            rname=transformRoleName(rs.getString(3));
                            if(UserMigrationTool.this.userDAO.addPermissionByOpenid(oid,gname,rname)) {
                                migrateCount++;
                                System.out.println("**Migrated Permission #"+i+": oid["+oid+"] ["+gname+"] ["+rname+"]");
                            }
                        }
                        oid=transformOpenid(rs.getString(1));
                        gname=transformGroupName(rs.getString(2));
                        rname=transformRoleName(rs.getString(3));
                        if(UserMigrationTool.this.userDAO.addPermissionByOpenid(oid,gname,rname)) {
                            migrateCount++;
                            System.out.println("Migrated Permission #"+i+": oid["+oid+"] ["+gname+"] ["+rname+"]");
                        }
                    }catch(ESGFDataAccessException e) {
                        log.error("Sorry, could NOT create permission tuple: oid["+oid+"] g["+gname+"] r["+rname+"] ");
                        errorCount++;
                    }
                    i++;
                }
                log.info("Inspected "+i+" Permission Records: Migrated "+migrateCount+", with "+errorCount+" failed");
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
        try {
            //Enter the connection URI information
            //setup source connection
            Properties props = new Properties();
            if(args.length >= 4) {
                for(int i=0;i<(args.length-1);i++) {
                    System.out.println();
                    if("-U".equals(args[i])) {
                        i++;
                        System.out.print("user = ");
                        if(args[i].startsWith("-")) { --i; continue; }
                        props.setProperty("db.user",args[i]);
                        System.out.print(args[i]);
                        continue;
                    }
                    if("-h".equals(args[i])) {
                        i++;
                        System.out.print("host = ");
                        if(args[i].startsWith("-")) { --i; continue; }
                        props.setProperty("db.host",args[i]);
                        System.out.print(args[i]);
                        continue;
                    }
                    if("-p".equals(args[i])) {
                        i++;
                        System.out.print("port = ");
                        if(args[i].startsWith("-")) { --i; continue; }
                        props.setProperty("db.port",args[i]);
                        System.out.print(args[i]);
                        continue;
                    }
                    if("-d".equals(args[i])) {
                        i++;
                        System.out.print("database = ");
                        if(args[i].startsWith("-")) { --i; continue; }
                        props.setProperty("db.database",args[i]);
                        System.out.print(args[i]);
                        continue;
                    }
                }
                System.out.println();
            }else {
                System.out.println("\nUsage:");
                System.out.println("  java -jar esgf-security-user-migration-x.x.x.jar -U <username> -h <host> -p <port> -d <database>");
                System.out.println("  (hit return and then enter your password)\n");
                System.exit(1);
            }

            char password[] = null;
            try {
                password = PasswordField.getPassword(System.in, "Enter source database password: ");
            }catch(IOException ioe) {
                System.err.println("Ooops sumthin' ain't right with the input... :-(");
                System.exit(1);
                ioe.printStackTrace();
            }
            if(password == null ) {
                System.out.println("No password entered");
                System.exit(1);
            }

            props.setProperty("db.password",String.valueOf(password));

            System.out.println();

            (new UserMigrationTool()).init(props).migrate();

        }catch(Throwable t) {
            System.out.println(t.getMessage());
            System.out.println("\n Sorry, please check your database connection information again, was not able to migrate users :-(\n");
            System.exit(1);
        }

        System.out.println("\ndone :-)\n");
        System.out.println(" Thank you for migrating to the ESGF P2P Node");
        System.out.println(" http://esgf.org\n");
    }

}
