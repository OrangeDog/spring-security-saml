/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.springframework.security.saml2.serviceprovider.servlet.logout;

import java.util.List;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.saml2.Saml2Exception;
import org.springframework.security.saml2.Saml2ValidationResult;
import org.springframework.security.saml2.model.Saml2Object;
import org.springframework.security.saml2.model.authentication.Saml2Issuer;
import org.springframework.security.saml2.model.authentication.Saml2LogoutResponse;
import org.springframework.security.saml2.model.authentication.Saml2LogoutSaml2Request;
import org.springframework.security.saml2.model.authentication.Saml2NameIdPrincipal;
import org.springframework.security.saml2.model.authentication.Saml2Status;
import org.springframework.security.saml2.model.authentication.Saml2StatusCode;
import org.springframework.security.saml2.model.metadata.Saml2Binding;
import org.springframework.security.saml2.model.metadata.Saml2Endpoint;
import org.springframework.security.saml2.model.metadata.Saml2IdentityProviderMetadata;
import org.springframework.security.saml2.model.metadata.Saml2ServiceProviderMetadata;
import org.springframework.security.saml2.model.metadata.Saml2SsoProvider;
import org.springframework.security.saml2.provider.HostedSaml2ServiceProvider;
import org.springframework.security.saml2.serviceprovider.authentication.Saml2Authentication;
import org.springframework.security.saml2.serviceprovider.binding.Saml2HttpMessageData;
import org.springframework.security.saml2.serviceprovider.servlet.util.Saml2ServiceProviderMethods;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;

import static java.lang.String.format;
import static java.util.Optional.ofNullable;

public class DefaultSaml2LogoutHttpMessageResolver implements Saml2LogoutHttpMessageResolver {

	private static Log logger = LogFactory.getLog(DefaultSaml2LogoutHttpMessageResolver.class);
	private final Saml2ServiceProviderMethods serviceProviderMethods;

	public DefaultSaml2LogoutHttpMessageResolver(Saml2ServiceProviderMethods serviceProviderMethods) {
		this.serviceProviderMethods = serviceProviderMethods;
	}

	@Override
	public Saml2HttpMessageData resolveLogoutHttpMessage(Saml2Authentication authentication,
														 HttpServletRequest request,
														 HttpServletResponse response) {
		Saml2Object logoutRequest = serviceProviderMethods.getSamlRequest(request);
		Saml2Object logoutResponse = serviceProviderMethods.getSamlResponse(request);
		if (ofNullable(logoutRequest).isPresent()) {
			return receivedLogoutRequest(request, response, authentication, logoutRequest);
		}
		else if (ofNullable(logoutResponse).isPresent()) {
			return null;
		}
		else if (authentication != null) {
			return spInitiatedLogout(request, response, authentication);
		}
		else {
			//just perform a simple logout
			return null;
		}
	}

	private Saml2HttpMessageData receivedLogoutRequest(HttpServletRequest request,
													   HttpServletResponse response,
													   Saml2Authentication authentication,
													   Saml2Object logoutRequest) {

		if (!(logoutRequest instanceof Saml2LogoutSaml2Request)) {
			throw new Saml2Exception("Invalid logout request:" + logoutRequest);
		}
		Saml2LogoutSaml2Request lr = (Saml2LogoutSaml2Request) logoutRequest;
		HostedSaml2ServiceProvider provider = serviceProviderMethods.getProvider(request);
		Saml2ValidationResult validate = serviceProviderMethods.getValidator().validate(lr, provider);
		if (validate.hasErrors()) {
			throw new Saml2Exception(validate.toString());
		}

		Saml2IdentityProviderMetadata idp = provider.getRemoteProvider(lr.getIssuer().getValue());
		Saml2LogoutResponse logoutResponse = logoutResponse(provider, lr, idp);
		Saml2HttpMessageData mvcData = new Saml2HttpMessageData(
			null,
			logoutResponse,
			new Saml2Endpoint()
				.setLocation(logoutResponse.getDestination())
				.setBinding(Saml2Binding.REDIRECT),
			getLogoutRelayState(request, idp)
		);
		return mvcData;
	}

	private Saml2HttpMessageData spInitiatedLogout(HttpServletRequest request,
												   HttpServletResponse response,
												   Saml2Authentication authentication) {
		logger.debug(format("Initiating SP logout for SP:%s", authentication.getHoldingEntityId()));
		HostedSaml2ServiceProvider provider = serviceProviderMethods.getProvider(request);
		Saml2ServiceProviderMetadata sp = provider.getMetadata();
		Saml2IdentityProviderMetadata idp = provider.getRemoteProvider(authentication.getAssertingEntityId());
		Saml2LogoutSaml2Request lr =
			logoutRequest(provider.getMetadata(), idp, (Saml2NameIdPrincipal) authentication.getSamlPrincipal());
		if (lr.getDestination() != null) {
			logger.debug("Sending logout request through redirect.");
			Saml2HttpMessageData mvcData = new Saml2HttpMessageData(
				lr,
				null,
				lr.getDestination(),
				getLogoutRelayState(
					request,
					idp
				)
			);
			return mvcData;
		}
		else {
			throw new Saml2Exception("Unable to send logout request. No destination set.");
		}
	}

	private Saml2LogoutResponse logoutResponse(
		HostedSaml2ServiceProvider local,
		Saml2LogoutSaml2Request request,
		Saml2IdentityProviderMetadata recipient) {
		List<Saml2SsoProvider> ssoProviders = recipient.getSsoProviders();
		Saml2Endpoint destination = serviceProviderMethods.getPreferredEndpoint(
			ssoProviders.get(0).getSingleLogoutService(),
			null,
			-1
		);
		return new Saml2LogoutResponse()
			.setId("LRP" + UUID.randomUUID().toString())
			.setInResponseTo(request != null ? request.getId() : null)
			.setDestination(destination != null ? destination.getLocation() : null)
			.setStatus(new Saml2Status().setCode(Saml2StatusCode.SUCCESS))
			.setIssuer(new Saml2Issuer().setValue(local.getMetadata().getEntityId()))
			.setSigningKey(
				local.getMetadata().getSigningKey(),
				local.getMetadata().getAlgorithm(),
				local.getMetadata().getDigest()
			)
			.setIssueInstant(new DateTime())
			.setVersion("2.0");
	}

	private String getLogoutRelayState(HttpServletRequest request, Saml2IdentityProviderMetadata idp) {
		return request.getParameter("RelayState");
	}

	private Saml2LogoutSaml2Request logoutRequest(
		Saml2ServiceProviderMetadata local,
		Saml2IdentityProviderMetadata idp,
		Saml2NameIdPrincipal principal) {
		List<Saml2SsoProvider> ssoProviders = idp.getSsoProviders();
		Saml2LogoutSaml2Request result = new Saml2LogoutSaml2Request()
			.setId("LRQ" + UUID.randomUUID().toString())
			.setDestination(
				serviceProviderMethods.getPreferredEndpoint(
					ssoProviders.get(0).getSingleLogoutService(),
					null,
					-1
				)
			)
			.setIssuer(new Saml2Issuer().setValue(local.getEntityId()))
			.setIssueInstant(DateTime.now())
			.setNameId(principal)
			.setSigningKey(local.getSigningKey(), local.getAlgorithm(), local.getDigest());

		return result;
	}
}
