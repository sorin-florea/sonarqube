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
package org.sonar.server.projectdump.ws;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.web.UserRole;
import org.sonar.ce.task.CeTask;
import org.sonar.db.DbTester;
import org.sonar.db.ce.CeTaskTypes;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ResourceTypesRule;
import org.sonar.db.user.UserDto;
import org.sonar.server.ce.projectdump.ExportSubmitter;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.db.component.ComponentTesting.newPrivateProjectDto;
import static org.sonar.test.JsonAssert.assertJson;

public class ExportActionTest {

  private static final String TASK_ID = "THGTpxcB-iU5OvuD2ABC";
  private static final String PROJECT_ID = "ABCTpxcB-iU5Ovuds4rf";
  private static final String PROJECT_KEY = "the_project_key";
  private static final String PROJECT_NAME = "The Project Name";

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public DbTester db = DbTester.create();

  private final ExportSubmitter exportSubmitter = mock(ExportSubmitter.class);
  private final ResourceTypesRule resourceTypes = new ResourceTypesRule().setRootQualifiers(Qualifiers.PROJECT, Qualifiers.VIEW);
  private final ProjectDumpWsSupport projectDumpWsSupport = new ProjectDumpWsSupport(db.getDbClient(), userSession, new ComponentFinder(db.getDbClient(), resourceTypes));
  private final ExportAction underTest = new ExportAction(projectDumpWsSupport, userSession, exportSubmitter);
  private final WsActionTester actionTester = new WsActionTester(underTest);
  private ComponentDto project;

  @Before
  public void setUp() {
    project = db.components().insertComponent(newPrivateProjectDto(PROJECT_ID).setDbKey(PROJECT_KEY).setName(PROJECT_NAME));
  }

  @Test
  public void response_example_is_defined() {
    assertThat(responseExample()).isNotEmpty();
  }

  @Test
  public void fails_if_missing_project_key() {
    logInAsProjectAdministrator("foo");

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'key' parameter is missing");

    actionTester.newRequest().setMethod("POST").execute();
  }

  @Test
  public void fails_if_not_project_administrator() {
    userSession.logIn();

    expectedException.expect(ForbiddenException.class);

    actionTester.newRequest().setMethod("POST").setParam("key", project.getDbKey()).execute();
  }

  @Test
  public void triggers_CE_task() {
    UserDto user = db.users().insertUser();
    userSession.logIn(user).addProjectPermission(UserRole.ADMIN, project);

    when(exportSubmitter.submitProjectExport(project.getDbKey(), user.getUuid())).thenReturn(createResponseExampleTask());
    TestResponse response = actionTester.newRequest().setMethod("POST").setParam("key", project.getDbKey()).execute();

    assertJson(response.getInput()).isSimilarTo(responseExample());
  }

  @Test
  public void fails_to_trigger_task_if_anonymous() {
    userSession.anonymous();

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    actionTester.newRequest().setMethod("POST").setParam("key", project.getDbKey()).execute();
  }

  @Test
  public void triggers_CE_task_if_project_admin() {
    UserDto user = db.users().insertUser();
    userSession.logIn(user).addProjectPermission(UserRole.ADMIN, project);

    when(exportSubmitter.submitProjectExport(project.getDbKey(), user.getUuid())).thenReturn(createResponseExampleTask());
    TestResponse response = actionTester.newRequest().setMethod("POST").setParam("key", project.getDbKey()).execute();

    assertJson(response.getInput()).isSimilarTo(responseExample());
  }

  @Test
  public void fail_when_using_branch_db_key() {
    ComponentDto project = db.components().insertPublicProject();
    ComponentDto branch = db.components().insertProjectBranch(project);
    userSession.logIn().addProjectPermission(UserRole.ADMIN, project);

    expectedException.expect(NotFoundException.class);

    actionTester.newRequest()
      .setMethod("POST")
      .setParam("key", branch.getDbKey())
      .execute();
  }

  private void logInAsProjectAdministrator(String login) {
    userSession.logIn(login).addProjectPermission(UserRole.ADMIN, project);
  }

  private String responseExample() {
    return actionTester.getDef().responseExampleAsString();
  }

  private CeTask createResponseExampleTask() {
    CeTask.Component component = new CeTask.Component(project.uuid(), project.getDbKey(), project.name());
    return new CeTask.Builder()
      .setType(CeTaskTypes.PROJECT_EXPORT)
      .setUuid(TASK_ID)
      .setComponent(component)
      .setMainComponent(component)
      .build();
  }
}
