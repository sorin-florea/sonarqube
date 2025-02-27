/*
 * SonarQube
 * Copyright (C) 2009-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.authentication;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.user.SessionTokenDto;
import org.sonar.db.user.UserDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.sonar.db.user.UserTesting.newUserDto;

public class JwtHttpHandlerTest {

  private static final String JWT_TOKEN = "TOKEN";
  private static final String CSRF_STATE = "CSRF_STATE";

  private static final long NOW = 10_000_000_000L;
  private static final long FOUR_MINUTES_AGO = NOW - 4 * 60 * 1000L;
  private static final long SIX_MINUTES_AGO = NOW - 6 * 60 * 1000L;
  private static final long TEN_DAYS_AGO = NOW - 10 * 24 * 60 * 60 * 1000L;

  private static final long IN_FIVE_MINUTES = NOW + 5 * 60 * 1000L;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public DbTester db = DbTester.create();

  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();
  private ArgumentCaptor<Cookie> cookieArgumentCaptor = ArgumentCaptor.forClass(Cookie.class);
  private ArgumentCaptor<JwtSerializer.JwtSession> jwtArgumentCaptor = ArgumentCaptor.forClass(JwtSerializer.JwtSession.class);
  private HttpServletRequest request = mock(HttpServletRequest.class);
  private HttpServletResponse response = mock(HttpServletResponse.class);
  private HttpSession httpSession = mock(HttpSession.class);
  private System2 system2 = spy(System2.INSTANCE);
  private MapSettings settings = new MapSettings();
  private JwtSerializer jwtSerializer = mock(JwtSerializer.class);
  private JwtCsrfVerifier jwtCsrfVerifier = mock(JwtCsrfVerifier.class);

  private JwtHttpHandler underTest = new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);

  @Before
  public void setUp() {
    when(system2.now()).thenReturn(NOW);
    when(request.getSession()).thenReturn(httpSession);
    when(jwtSerializer.encode(any(JwtSerializer.JwtSession.class))).thenReturn(JWT_TOKEN);
    when(jwtCsrfVerifier.generateState(eq(request), eq(response), anyInt())).thenReturn(CSRF_STATE);
  }

  @Test
  public void create_token() {
    UserDto user = db.users().insertUser();
    underTest.generateToken(user, request, response);

    Optional<Cookie> jwtCookie = findCookie("JWT-SESSION");
    assertThat(jwtCookie).isPresent();
    verifyCookie(jwtCookie.get(), JWT_TOKEN, 3 * 24 * 60 * 60);

    verify(jwtSerializer).encode(jwtArgumentCaptor.capture());
    verifyToken(jwtArgumentCaptor.getValue(), user, 3 * 24 * 60 * 60, NOW);
    verifySessionTokenInDb(jwtArgumentCaptor.getValue());
  }

  @Test
  public void generate_csrf_state_when_creating_token() {
    UserDto user = db.users().insertUser();
    underTest.generateToken(user, request, response);

    verify(jwtCsrfVerifier).generateState(request, response, 3 * 24 * 60 * 60);

    verify(jwtSerializer).encode(jwtArgumentCaptor.capture());
    JwtSerializer.JwtSession token = jwtArgumentCaptor.getValue();
    assertThat(token.getProperties()).containsEntry("xsrfToken", CSRF_STATE);
  }

  @Test
  public void generate_token_is_using_session_timeout_from_settings() {
    UserDto user = db.users().insertUser();
    int sessionTimeoutInMinutes = 10;
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", sessionTimeoutInMinutes);

    underTest = new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
    underTest.generateToken(user, request, response);

    verify(jwtSerializer).encode(jwtArgumentCaptor.capture());
    verifyToken(jwtArgumentCaptor.getValue(), user, sessionTimeoutInMinutes * 60, NOW);
  }

  @Test
  public void session_timeout_property_cannot_be_updated() {
    UserDto user = db.users().insertUser();
    int firstSessionTimeoutInMinutes = 10;
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", firstSessionTimeoutInMinutes);

    underTest = new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
    underTest.generateToken(user, request, response);

    // The property is updated, but it won't be taking into account
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", 15);
    underTest.generateToken(user, request, response);
    verify(jwtSerializer, times(2)).encode(jwtArgumentCaptor.capture());
    verifyToken(jwtArgumentCaptor.getAllValues().get(0), user, firstSessionTimeoutInMinutes * 60, NOW);
    verifyToken(jwtArgumentCaptor.getAllValues().get(1), user, firstSessionTimeoutInMinutes * 60, NOW);
  }

  @Test
  public void session_timeout_property_cannot_be_zero() {
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", 0);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Property sonar.web.sessionTimeoutInMinutes must be higher than 5 minutes and must not be greater than 3 months. Got 0");

    new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
  }

  @Test
  public void session_timeout_property_cannot_be_negative() {
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", -10);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Property sonar.web.sessionTimeoutInMinutes must be higher than 5 minutes and must not be greater than 3 months. Got -10");

    new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
  }

  @Test
  public void session_timeout_property_cannot_be_set_to_five_minutes() {
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", 5);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Property sonar.web.sessionTimeoutInMinutes must be higher than 5 minutes and must not be greater than 3 months. Got 5 minutes");

    new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
  }

  @Test
  public void session_timeout_property_cannot_be_greater_than_three_months() {
    settings.setProperty("sonar.web.sessionTimeoutInMinutes", 4 * 30 * 24 * 60);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Property sonar.web.sessionTimeoutInMinutes must be higher than 5 minutes and must not be greater than 3 months. Got 172800 minutes");

    new JwtHttpHandler(system2, dbClient, settings.asConfig(), jwtSerializer, jwtCsrfVerifier);
  }

  @Test
  public void validate_token() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, NOW);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    assertThat(underTest.validateToken(request, response)).isPresent();

    verify(jwtSerializer, never()).encode(any(JwtSerializer.JwtSession.class));
  }

  @Test
  public void validate_token_refresh_session_when_refresh_time_is_reached() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // Token was created 10 days ago and refreshed 6 minutes ago
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, TEN_DAYS_AGO);
    claims.put("lastRefreshTime", SIX_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    assertThat(underTest.validateToken(request, response)).isPresent();

    verify(jwtSerializer).refresh(any(Claims.class), eq(NOW + 3 * 24 * 60 * 60 * 1000L));
    assertThat(dbClient.sessionTokensDao().selectByUuid(dbSession, sessionToken.getUuid()).get().getExpirationDate())
      .isNotEqualTo(IN_FIVE_MINUTES)
      .isEqualTo(NOW + 3 * 24 * 60 * 60 * 1000L);
  }

  @Test
  public void validate_token_does_not_refresh_session_when_refresh_time_is_not_reached() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // Token was created 10 days ago and refreshed 4 minutes ago
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, TEN_DAYS_AGO);
    claims.put("lastRefreshTime", FOUR_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    assertThat(underTest.validateToken(request, response)).isPresent();

    verify(jwtSerializer, never()).refresh(any(Claims.class), anyInt());
  }

  @Test
  public void validate_token_does_not_refresh_session_when_disconnected_timeout_is_reached() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // Token was created 4 months ago, refreshed 4 minutes ago, and it expired in 5 minutes
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, NOW - (4L * 30 * 24 * 60 * 60 * 1000));
    claims.put("lastRefreshTime", FOUR_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_does_not_refresh_session_when_user_is_disabled() {
    addJwtCookie();
    UserDto user = addUser(false);
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, NOW);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_does_not_refresh_session_when_token_is_no_more_valid() {
    addJwtCookie();

    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.empty());

    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_does_nothing_when_no_jwt_cookie() {
    underTest.validateToken(request, response);

    verifyNoInteractions(httpSession, jwtSerializer);
    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_does_nothing_when_empty_value_in_jwt_cookie() {
    when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("JWT-SESSION", "")});

    underTest.validateToken(request, response);

    verifyNoInteractions(httpSession, jwtSerializer);
    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_verify_csrf_state() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, NOW);
    claims.put("xsrfToken", CSRF_STATE);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateToken(request, response);

    verify(jwtCsrfVerifier).verifyState(request, CSRF_STATE, user.getUuid());
  }

  @Test
  public void validate_token_does_nothing_when_no_session_token_in_db() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // No SessionToken in DB
    Claims claims = createToken("ABCD", user.getUuid(), NOW, IN_FIVE_MINUTES);
    claims.put("lastRefreshTime", SIX_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateToken(request, response);

    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_does_nothing_when_expiration_date_from_session_token_is_expired() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // In SessionToken, the expiration date is expired...
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(FOUR_MINUTES_AGO));
    // ...whereas in the cookie, the expiration date is not expired
    Claims claims = createToken(sessionToken.getUuid(), user.getUuid(), NOW, IN_FIVE_MINUTES);
    claims.put("lastRefreshTime", SIX_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateToken(request, response);

    assertThat(underTest.validateToken(request, response)).isEmpty();
  }

  @Test
  public void validate_token_refresh_state_when_refreshing_token() {
    UserDto user = db.users().insertUser();
    addJwtCookie();
    // Token was created 10 days ago and refreshed 6 minutes ago
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, TEN_DAYS_AGO);
    claims.put("xsrfToken", "CSRF_STATE");
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.validateToken(request, response);

    verify(jwtSerializer).refresh(any(Claims.class), anyLong());
    verify(jwtCsrfVerifier).refreshState(request, response, "CSRF_STATE", 3 * 24 * 60 * 60);
  }

  @Test
  public void remove_token() {
    addJwtCookie();
    UserDto user = db.users().insertUser();
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    Claims claims = createToken(sessionToken, TEN_DAYS_AGO);
    claims.put("lastRefreshTime", FOUR_MINUTES_AGO);
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.of(claims));

    underTest.removeToken(request, response);

    verifyCookie(findCookie("JWT-SESSION").get(), null, 0);
    verify(jwtCsrfVerifier).removeState(request, response);
    assertThat(dbClient.sessionTokensDao().selectByUuid(dbSession, sessionToken.getUuid())).isNotPresent();
  }

  @Test
  public void does_not_remove_token_from_db_when_no_jwt_token_in_cookie() {
    addJwtCookie();
    UserDto user = db.users().insertUser();
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));
    when(jwtSerializer.decode(JWT_TOKEN)).thenReturn(Optional.empty());

    underTest.removeToken(request, response);

    verifyCookie(findCookie("JWT-SESSION").get(), null, 0);
    verify(jwtCsrfVerifier).removeState(request, response);
    assertThat(dbClient.sessionTokensDao().selectByUuid(dbSession, sessionToken.getUuid())).isPresent();
  }

  @Test
  public void does_not_remove_token_from_db_when_no_cookie() {
    UserDto user = db.users().insertUser();
    SessionTokenDto sessionToken = db.users().insertSessionToken(user, st -> st.setExpirationDate(IN_FIVE_MINUTES));

    underTest.removeToken(request, response);

    verifyCookie(findCookie("JWT-SESSION").get(), null, 0);
    verify(jwtCsrfVerifier).removeState(request, response);
    assertThat(dbClient.sessionTokensDao().selectByUuid(dbSession, sessionToken.getUuid())).isPresent();
  }

  private void verifyToken(JwtSerializer.JwtSession token, UserDto user, long expectedExpirationDuration, long expectedRefreshTime) {
    assertThat(token.getExpirationTime()).isEqualTo(NOW + expectedExpirationDuration * 1000L);
    assertThat(token.getUserLogin()).isEqualTo(user.getUuid());
    assertThat(token.getProperties()).containsEntry("lastRefreshTime", expectedRefreshTime);
  }

  private Optional<Cookie> findCookie(String name) {
    verify(response).addCookie(cookieArgumentCaptor.capture());
    return cookieArgumentCaptor.getAllValues().stream()
      .filter(cookie -> name.equals(cookie.getName()))
      .findFirst();
  }

  private void verifyCookie(Cookie cookie, @Nullable String value, int expiry) {
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getMaxAge()).isEqualTo(expiry);
    assertThat(cookie.getSecure()).isFalse();
    assertThat(cookie.getValue()).isEqualTo(value);
  }

  private void verifySessionTokenInDb(JwtSerializer.JwtSession jwtSession) {
    Map<String, Object> map = db.selectFirst(dbSession, "select st.uuid as \"uuid\", " +
      "st.user_uuid as \"userUuid\", " +
      "st.expiration_date as \"expirationDate\" " +
      "from session_tokens st ");
    assertThat(map)
      .contains(
        entry("uuid", jwtSession.getSessionTokenUuid()),
        entry("expirationDate", jwtSession.getExpirationTime()));
  }

  private UserDto addUser(boolean active) {
    UserDto user = newUserDto()
      .setActive(active);
    dbClient.userDao().insert(dbSession, user);
    dbSession.commit();
    return user;
  }

  private Cookie addJwtCookie() {
    Cookie cookie = new Cookie("JWT-SESSION", JWT_TOKEN);
    when(request.getCookies()).thenReturn(new Cookie[] {cookie});
    return cookie;
  }

  private Claims createToken(SessionTokenDto sessionToken, long createdAt) {
    return createToken(sessionToken.getUuid(), sessionToken.getUserUuid(), createdAt, sessionToken.getExpirationDate());
  }

  private Claims createToken(String uuid, String userUuid, long createdAt, long expiredAt) {
    DefaultClaims claims = new DefaultClaims();
    claims.setId(uuid);
    claims.setSubject(userUuid);
    claims.setIssuedAt(new Date(createdAt));
    claims.setExpiration(new Date(expiredAt));
    claims.put("lastRefreshTime", createdAt);
    return claims;
  }
}
