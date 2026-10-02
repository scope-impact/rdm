from xml.etree import ElementTree

DISABLED_PREFIX = 'DISABLED_'


def xml_load(xml_path):
    return ElementTree.parse(xml_path)


def check_disabled(name):
    if name.startswith(DISABLED_PREFIX):
        return name[len(DISABLED_PREFIX):], True
    else:
        return name, False


# How bad an outcome is: two results for one test keep the worse.
_RANK = {"pass": 0, "skip": 1, "fail": 2}
# qttest incident types: a fail, an unexpected pass (or a failed benchmark) fail.
_QT = {"fail": "fail", "xpass": "fail", "bfail": "fail", "skip": "skip"}


def _keep_worse(results, name, result, message):
    earlier = results.get(name)
    if earlier is None or _RANK[result] > _RANK[earlier["result"]]:
        results[name] = {"name": name, "result": result, "message": message}


def flattened_gtest_results(test_results):
    """gtest or xunit cases: a failure or an error is a fail, a disabled,
    not-run or skipped case a skip, the rest a pass. A case is read from its
    own suite only, and a name seen twice keeps the worse result."""
    flattened_results = {}
    for test_suite in test_results.iter('testsuite'):
        suite_name, suite_disabled = check_disabled(test_suite.get('name', '_'))
        for test_case in test_suite.findall('testcase'):
            case_name, case_disabled = check_disabled(test_case.get('name', '_'))
            test_name = ".".join([suite_name, case_name])
            status = test_case.get('status')
            problem = test_case.find('failure')
            if problem is None:
                problem = test_case.find('error')
            if suite_disabled or case_disabled or (status is not None and status != 'run'):
                result, message = 'skip', None
            elif problem is not None:
                result, message = 'fail', problem.get('message')
            elif test_case.find('skipped') is not None or test_case.get('result') in ('skipped', 'suppressed'):
                result, message = 'skip', None
            else:
                result, message = 'pass', None
            _keep_worse(flattened_results, test_name, result, message)
    return flattened_results


def flattened_qttest_results(test_results):
    """qttest functions: one fails when any of its incidents does."""
    flattened_results = {}
    for test_case in test_results.iter('TestCase'):
        case_name = test_case.get('name', '_')
        for test_function in test_case.iter('TestFunction'):
            test_name = ".".join([case_name, test_function.get('name', '_')])
            for incident in test_function.iter('Incident'):
                description = incident.find('Description')
                message = None if description is None else description.text
                _keep_worse(flattened_results, test_name, _QT.get(incident.get("type"), "pass"), message)
    return flattened_results


def auto_translator(test_results):
    if test_results.find('Environment') is not None:
        return flattened_qttest_results(test_results)
    else:
        return flattened_gtest_results(test_results)
