"""Marathon acceptance tests for DC/OS regarding network partitioning"""

import os

import pytest
import requests

from common import *
from shakedown import *
from utils import *


PACKAGE_NAME = 'marathon'
PACKAGE_APP_ID = 'marathon-user'
DCOS_SERVICE_URL = dcos_service_url(PACKAGE_APP_ID)
TOKEN = dcos_acs_token()


def setup_module(module):
    # verify test system requirements are met (number of nodes needed)
    agents = get_private_agents()
    print(agents)
    if len(agents) < 2:
        assert False, "Network tests require at least 2 private agents"


@pytest.mark.sanity
def test_mom_with_network_failure():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures
    simulated by knocking out ports
    """

    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to install'

    wait_for_service_url(PACKAGE_APP_ID)

    # get MoM ip
    service_ips = get_service_ips(PACKAGE_NAME, "marathon-user")
    for mom_ip in service_ips:
        break

    print("MoM IP: " + mom_ip)
    # copy files
    # master
    copy_file_to_master("{}/large-sleep.json".format(fixture_dir()))
    copy_file_to_master("{}/net-services-master.sh".format(fixture_dir()))

    # we have to wait until the admin router is mapped
    wait_for_service_url(PACKAGE_APP_ID)

    # launch job on MoM
    # TODO:  switch to using Marathon client to MoM
    installAppCurlCommand = "curl -X POST -H 'Authorization: token=" + TOKEN + "' " + DCOS_SERVICE_URL + "/v2/apps -d @large-sleep.json --header 'Content-Type: application/json' "
    run_command_on_master(installAppCurlCommand)

    wait_for_task("marathon-user", "sleep")

    # grab the sleep taskID
    json = get_json(__marathon_url("apps/sleep", "marathon-user"))
    original_sleep_task_id = json["app"]["tasks"][0]["id"]

    mom_task_ips = get_service_ips("marathon-user", "sleep")
    for task_ip in mom_task_ips:
        break

    print("\nTask IP: " + task_ip)

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    service_delay()

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    wait_for_service_url(PACKAGE_APP_ID)
    wait_for_task("marathon-user", "sleep")

    json = get_json(__marathon_url("apps/sleep", "marathon-user"))
    current_sleep_task_id = json["app"]["tasks"][0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"
    teardown_marathon_sleep()


@pytest.mark.sanity
def test_mom_with_network_failure_bounce_master():
    """Marathon on Marathon (MoM) tests for DC/OS with network failures simulated by
    knocking out ports
    """
    # Install MoM
    install_package_and_wait(PACKAGE_NAME)
    assert package_installed(PACKAGE_NAME), 'Package failed to install'

    wait_for_service_url(PACKAGE_APP_ID)

    # get MoM ip
    service_ips = get_service_ips(PACKAGE_NAME, "marathon-user")
    for mom_ip in service_ips:
        break

    print("MoM IP: " + mom_ip)
    # copy files
    # master
    copy_file_to_master("{}/large-sleep.json".format(fixture_dir()))
    copy_file_to_master("{}/net-services-master.sh".format(fixture_dir()))

    # we have to wait until the admin router is mapped
    wait_for_service_url(PACKAGE_APP_ID)

    # launch job on MoM
    # TODO:  switch to using Marathon client to MoM
    installAppCurlCommand = "curl -X POST -H 'Authorization: token=" + TOKEN + "' " + DCOS_SERVICE_URL + "/v2/apps -d @large-sleep.json --header 'Content-Type: application/json' "
    run_command_on_master(installAppCurlCommand)

    wait_for_task("marathon-user", "sleep")

    # grab the sleep taskID
    json = get_json(__marathon_url("apps/sleep", "marathon-user"))
    original_sleep_task_id = json["app"]["tasks"][0]["id"]

    mom_task_ips = get_service_ips("marathon-user", "sleep")
    for task_ip in mom_task_ips:
        break

    print("\nTask IP: " + task_ip)

    # PR for network partitioning in shakedown makes this better
    # take out the net
    partition_agent(mom_ip)
    partition_agent(task_ip)

    # wait for a min
    service_delay()

    # bounce master
    run_command_on_master("sudo systemctl restart dcos-mesos-master")

    # bring the net up
    reconnect_agent(mom_ip)
    reconnect_agent(task_ip)

    service_delay()
    wait_for_service_url(PACKAGE_APP_ID)
    wait_for_task("marathon-user", "sleep")

    json = get_json(__marathon_url("apps/sleep", "marathon-user"))
    current_sleep_task_id = json["app"]["tasks"][0]["id"]

    assert current_sleep_task_id == original_sleep_task_id, "Task ID shouldn't change"
    teardown_marathon_sleep()


def teardown_module(module):
    # pytest teardown do not seem to be working
    print("teardown...")
    teardown_marathon_sleep()


def teardown_marathon_sleep():
    removeAppCurlCommand = "curl -X DELETE -H 'Authorization: token=" + TOKEN + "' " + DCOS_SERVICE_URL + "/v2/apps/sleep"
    run_command_on_master(removeAppCurlCommand)
    service_delay(15)
    try:
        uninstall_package_and_wait(PACKAGE_NAME)
    except Exception as e:
        pass

    delete_zk_node("universe/marathon-user")


# we need in shakedown!!
def __marathon_url(command, marathon_id="marathon"):
    return dcos_service_url(marathon_id) + '/v2/' + command


def get_json(url, req_json=None, retry=False, print_response=True):
    return print_json(get_raw(url, req_json, retry), print_response)


def delete_raw(url, req_json=None, retry=False):
    return request_raw("delete", url, req_json, retry)


def get_raw(url, req_json=None, retry=False):
    return request_raw("get", url, req_json, retry)


def request_raw(method, url, req_json=None, retry=False, req_headers={}):
    req_headers = {}
    auth_token = TOKEN

    # insert auth token, if any, into request headers
    if auth_token:
        req_headers["Authorization"] = "token={}".format(auth_token)

    func = lambda: __handle_response(
        method.upper(), url, requests.request(method, url, json=req_json, headers=req_headers))
    if retry:
        return __retry_func(func)
    else:
        return func()


def service_delay(delay=120):
    time.sleep(delay)


def print_json(response, print_response=True):
    response_json = response.json()
    if print_response:
        print("Got response for %s %s:\n%s" % (
            response.request.method, response.request.url, response_json))
    return response_json


def __handle_response(httpcmd, url, response):
    # http code 200-299 or 503 => success!
    if (response.status_code < 200 or response.status_code >= 300) and response.status_code != 503:
        errmsg = "Error code in response to %s %s: %s/%s" % (
            httpcmd, url, response.status_code, response.content)
        print(errmsg)
        response.raise_for_status()
    return response


def partition_agent(hostname):
    """Partition a node from all network traffic except for SSH and loopback"""

    copy_file_to_agent(hostname, "{}/net-services-agent.sh".format(fixture_dir()))
    print("partitioning {}".format(hostname))
    run_command_on_agent(hostname, 'sh net-services-agent.sh fail')


def reconnect_agent(hostname):
    """Reconnect a node to cluster"""

    run_command_on_agent(hostname, 'sh net-services-agent.sh')
