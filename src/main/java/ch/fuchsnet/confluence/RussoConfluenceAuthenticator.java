package ch.fuchsnet.confluence;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.atlassian.confluence.event.events.security.LoginEvent;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.seraph.auth.AuthenticatorException;
import com.atlassian.confluence.user.ConfluenceAuthenticator;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.spring.container.ContainerManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;

/*
Thanks and credits go to Christian Fuchs
https://github.com/Fuchs/confluence-remote-user-sso

Remote User Single Sign On Authenticator russo-confluence: 
Authenticating to Confluence with the X_Forwarded_User HTTP header
Copyright (C) 2014  Christian Loosli

This software may be modified and distributed under the terms
of the MIT license.  See the COPYING file for details.
 */

/**
 * Extension of DefaultAuthenticator that uses the Apache set X-Forwarded-User
 * header in a HTTPRequest object for single sign on.
 * 
 * @author Christian Loosli
 *
 */
public class RussoConfluenceAuthenticator extends ConfluenceAuthenticator {

	// Header we read. Has to be lowercase even if the header is set uppercase in apache
	//private static final String strHeaderName = "x-forwarded-user";
	private static final String strHeaderName = "x-rp-usr";

	private static final long serialVersionUID = 1807345345435345234L;

	private static final Logger log = LoggerFactory.getLogger(ConfluenceAuthenticator.class);

	private static PermissionManager permissionManager = null;

	/**
	 * Default method getting the user, first calls the Confluence based method,
	 * then checks for X-Forwarded-User in the header. This should ensure that
	 * everything using other methods than Apache Kerberos Auth should still work,
	 * but in addition to that, the header set after Kerberos auth will be
	 * considered and should also allow a log-in.
	 * 
	 * @param request  The request containing the headers
	 * @param response The response sent
	 * @return The user principal, can be null if authentication failed.
	 */
	public Principal getUser(HttpServletRequest request, HttpServletResponse response) {

		Principal user = null;
		ConfluenceUser confluenceUser = null;

		try {
			// This shall also take care of the user already being logged in, as the parent
			// checks that.
			user = super.getUser(request, response);
			String username = request.getHeader(strHeaderName);

			// Neither an already existing user nor a forwarded one in the header.
			// This will return null, which should have Confluence redirect the user to the
			// configurated login page
			if ((user == null) && (username == null)) {
				return user;
			}

			if (user != null) {
				// user is already known and given from the session
				if ((username != null) && (user.getName().equals(username))) {
					// it matches the username
					return user;
				} else {
					// doesn't match the username, we continue anyway with this user
					return user;
				}
			}

			final String remoteIP = request.getRemoteAddr();
			final String remoteHost = request.getRemoteHost();

			// up until here, there wasn't a user given in the session, means it is a new
			// one
			try {
				// retrieve a user from the application with the username from the given Header
				confluenceUser = super.getUser(username);
				if (confluenceUser != null) {
					// user found
					PermissionManager pMgr = this.getPermissionManager();
					if (pMgr != null && !pMgr.hasPermission(confluenceUser, Permission.VIEW,
							PermissionManager.TARGET_APPLICATION)) {
						// seems to be an anonymous user
						// https://developer.atlassian.com/server/confluence/how-do-i-tell-if-a-user-has-permission-to/
						return null;
					}
					// if it went up to here, it is a real licensed application user
					user = (Principal) confluenceUser;
					log.info("RUSSO User sucessfully logged in " + username);
					// Firing this event is necessary to ensure the user's personal information is
					// initialized correctly.
					getEventPublisher().publish(new LoginEvent(this, username, request.getSession().getId(), remoteHost,
							remoteIP, LoginEvent.UNKNOWN));
				}
			} catch (Exception e) {
				log.error("Failed to do RUSSO authentication check for user:" + user, e);
			}
			// return what we found up to here, even null if nothing found
			return user;
		} catch (Exception e) { // catch class cast exception
			return user;
		}
	}

	@Override
	protected boolean authenticate(Principal pPrincipal, String pStrPwd) throws AuthenticatorException {
		return super.authenticate(pPrincipal, pStrPwd);
	}

	@Override
	protected ConfluenceUser getUser(String pStrUsername) {
		return super.getUser(pStrUsername);
	}

	/**
	 * Retrieve Singleton PersmissionManager from the Application
	 * 
	 * @return PermissionManager singleton
	 */
	protected PermissionManager getPermissionManager() {
		if (permissionManager == null) {
			permissionManager = (PermissionManager) ContainerManager.getComponent("permissionManager");
			log.debug("RUSSO PermissionManager successfully obtained");
		}
		return permissionManager;
	}

}