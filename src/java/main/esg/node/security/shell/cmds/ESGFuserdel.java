/***************************************************************************
*                                                                          *
*  Organization: Lawrence Livermore National Lab (LLNL)                    *
*   Directorate: Computation                                               *
*    Department: Computing Applications and Research                       *
*      Division: S&T Global Security                                       *
*        Matrix: Atmospheric, Earth and Energy Division                    *
*       Program: PCMDI                                                     *
*       Project: Earth Systems Grid Federation (ESGF) Data Node Software   *
*  First Author: Gavin M. Bell (gavin@llnl.gov)                            *
*                                                                          *
****************************************************************************
*                                                                          *
*   Copyright (c) 2009, Lawrence Livermore National Security, LLC.         *
*   Produced at the Lawrence Livermore National Laboratory                 *
*   Written by: Gavin M. Bell (gavin@llnl.gov)                             *
*   LLNL-CODE-420962                                                       *
*                                                                          *
*   All rights reserved. This file is part of the:                         *
*   Earth System Grid Federation (ESGF) Data Node Software Stack           *
*                                                                          *
*   For details, see http://esgf.org/esg-node/                             *
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
package esg.node.security.shell.cmds;

/**
   Description:
   ESGF's "realize" command..."

   This command takes a dataset directory and inspects its catalog to
   find missing files (files listed in the dataset catalog but not
   locally present on the filesystem) and brings them local. The
   second half of the 'replication' process - for a single dataset.
**/

import esg.node.security.*;

import esg.common.shell.*;
import esg.common.shell.cmds.*;

import org.apache.commons.cli.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.*;

public class ESGFuserdel extends ESGFSecurityCommand {

private static Log log = LogFactory.getLog(ESGFuserdel.class);

    public ESGFuserdel() { super(); }

    public void init(ESGFEnv env) { checkPermission(env); }
    public String getCommandName() { return "userdel"; }


    public ESGFEnv doEval(CommandLine line, ESGFEnv env) {
        log.trace("inside the \"userdel\" command's doEval");

        //Scrubbing... (need to go into cli code and toss in some regex's to clean this type of shit up)
        java.util.List<String> argsList = new java.util.ArrayList<String>();
        String[] args = null;
        for(String arg : line.getArgs()) {
            if(!arg.isEmpty()) {
                argsList.add(arg);
            }
        }
        args = argsList.toArray(new String[]{});

        String username = null;
        if(args.length > 0) {
            username = args[0];
            env.getWriter().println("User to delete is: ["+username+"]");
            env.getWriter().flush();
        }else {
            throw new esg.common.ESGRuntimeException("You must provide the username or openid to delete");
        }


        //------------------
        //Check access privs and setup resource object
        //------------------

        UserInfoCredentialedDAO userDAO = null;
        if (!(userDAO = getUserDAO(env)).checkCredentials()) {
            userDAO = null;
            throw new ESGFCommandPermissionException("Credentials are not sufficient, sorry...");
        }
        //------------------

        
        UserInfo user = null;
        if(username != null) user = userDAO.getUserById(username);

        if(null == user) {
            throw new esg.common.ESGRuntimeException("Sorry, your username ["+username+"] was not well formed");
        }
        
        if(!user.isValid()) {
            throw new esg.common.ESGRuntimeException("User ["+username+"] is NOT present in the system!");            
        }

        //------------------
        //NOW DO SOME LOGIC
        //------------------
        
        if(user.getUserName().equalsIgnoreCase("rootAdmin")) {
            throw new esg.common.ESGRuntimeException("Sorry, this operationis not permitted for user ["+username+"]");
        }
        
        env.getWriter().flush();
        try{
            String answer = env.getReader().readLine("Is this information correct and ready to be submitted? [Y/n] > ");
            if(!answer.equals("") && !answer.equalsIgnoreCase("y")) {
                return env;
            }
        }catch(java.io.IOException e) { System.err.println(e.getMessage()); }
        
        if (userDAO.deleteUserInfo(user)) {
            env.getWriter().println("[DELETED]");
        }else {
            env.getWriter().println("[FAILED]");
        }
        
        //------------------
        return env;
    }
}
