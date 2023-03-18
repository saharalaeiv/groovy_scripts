//Usage Regression Test

def getGeneralTestParams() {
    return [
        envId: 2,
        dbUrl: 'bigfoot-db-tract01.tract-staging.com',
        bapiCount: 4,
        bapiUrlTemplate: 'bapi-c6i0%s-bigfoot.perf.gtvtech.net',
        scriptsFolder: '/home/centos/jmeter-perfromance-tests',
        scriptsGitRepo: 'https://dev1.gotransverse.com/gitlab/qa/jmeter-perfromance-tests.git',
        accountCategoryId: 14,
        paymentTermId: 16,
        srCategoryId: 15,
        productCategoryId: 37,
        chargeCategoryId: 146,
        usageChargeCategoryId: 149,
        sridsCount: 1000,
        requestsCount: 1000
    ]
}

def arePreconditionsDone = false
def dbSizeBefore
def dbSizeAfter
def listOfStartedBAPIs = []
def bapiStatuses = []
def jobsStatuses = []
def parametersList = []
def parameterNames = []

parametersList.add(
    string(defaultValue: getGeneralTestParams().sridsCount.toString(), name: 'srids_count', description: 'SRIDs count')
)
parameterNames.add('srids_count')

parametersList.add(
    string(
        defaultValue: getGeneralTestParams().requestsCount.toString(),
        name: 'requests_count', description: 'requests count'
    )
)
parameterNames.add('requests_count')

parametersList.add(
    booleanParam(
        name='run_main_variant',
        defaultValue=true,
        description='Products:\n'
                    '- Subscription:\n'
                    '-- Rule mode: Taper\n'
                    '-- Rules:\n'
                    '----- limited: 10,000; text01=rate table; uom: SECOND;\n'
                    '----- unlimited: text01=rate table; uom: SECOND;\n'
                    'Accounts:\n'
                    '- count: 1000;\n'
                    '- orders: 1 subscription with SRID\n'
                    'Events:\n'
                    '- mode: FAIL_ON_EXISTING;\n'
                    '- uom: SECOND;\n'
                    '- events count: 1,000,000;\n'
                    '- SRIDs count:1000;\n'
                    '- batch size: 500;\n'
                    '- SRIDs in batch:\n'
                    '  batch 1{SRID1, SRID2..., SRID500},\n'
                    '  batch2 {SRID501, SRID502..., SRID1000},\n'
                    '  batch3{SRID1...., SRID500}...\n'
    )
)
parameterNames.add('run_main_variant')

parametersList.add(
    booleanParam(
        name: 'run_variant_1', defaultValue: true,
        description: 'OVERWRITE_ON_EXISTING(events from main variant)'
    )
)
parameterNames.add('run_variant_1')

parametersList.add(
    booleanParam(name: 'run_variant_3', defaultValue: true, description: '2 Rules (FlatUsageRate - rate 1)')
)
parameterNames.add('run_variant_3')

parametersList.add(
    booleanParam(name: 'run_variant_4', defaultValue: true, description: '85 Rules (FlatUsageRate - rate 1)')
)
parameterNames.add('run_variant_4')

parametersList.add(booleanParam(name: 'run_variant_5', defaultValue: true, description: '3 users'))
parameterNames.add('run_variant_5')

parametersList.add(string(defaultValue: '', name: 'build_id', description: 'Build Id'))
parameterNames.add('build_id')

parametersList.add(string(defaultValue: '', name: 'ticket', description: 'Ticket # from Jira'))
parameterNames.add('ticket')

parametersList.add(
    credentials(
        name: 'gitCredentials',
        description: 'checkout Jmeter scripts',
        defaultValue: 'Gitlab_PullUser',
        credentialType: "Username with password",
        required: true
    )
)
parameterNames.add('gitCredentials')

properties([parameters(parametersList)])

def missedParams = parameterNames.findAll { !params[it]?.trim() }

if (missedParams) {
    def errorMessage = 'Not all parameters were specified. Please reload the page and provide following parameter(s): '
                        + missedParams.join(', ')
    currentBuild.result = 'ERROR'
    error(errorMessage)
}

node {
    dir ("${currentBuild.number}") {
        sshagent (credentials: ['deployer']) {
            try {
                node('util-box-2-perm-slave') {
                    stage('Checkout JMeter scripts') {
                        checkout(
                            [
                                $class: 'GitSCM',
                                branches: [[name: '*/master']],
                                doGenerateSubmoduleConfigurations: false,
                                extensions: [
                                    [
                                        $class: 'RelativeTargetDirectory',
                                        relativeTargetDir: getGeneralTestParams().scriptsFolder
                                    ]
                                ],
                                submoduleCfg: [],
                                userRemoteConfigs: [
                                    [credentialsId: gitCredentials, url: getGeneralTestParams().scriptsGitRepo]
                                ]
                            ]
                        )
                    }
                }

                stage('Create preconditions(main variant)') {
                    if(params['run_main_variant']) {
                        createPreconditionsMain()
                        arePreconditionsDone = true
                    } else {
                        echo 'Usage Test(main variant) is skipped'
                    }
                }

                def testRunUUID = null

                stage('Run Usage Regression Test(main variant)') {
                    if(params['run_main_variant']) {
                        if (arePreconditionsDone) {
                            dbSizeBefore = getDBSize()
                            echo "db size before: ${dbSizeBefore} M"

                            def results = null
                            def preconditions = null
                            node('util-box-2-perm-slave') {
                                def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                                 '/configs/general_configs.properties'
                                def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                                def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.scripts'] + '/regression/usage_test.jmx'
                                def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_test/run' + currentBuild.number
                                def dataFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_preconditions/run' +
                                 currentBuild.number + '/'
                                def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.env'] + '/bigfoot.properties'
                                def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                                sh jmeterShPath +
                                    ' -t ' + scriptPath +
                                    ' -j ' + resultsFolder + '/all.log' +
                                    ' -l ' + resultsFolder + '/results.jtl' +
                                    ' -JresultsFolder=' + resultsFolder + '/ ' +
                                    ' -Jenv_properties_file=' + envConfigs + ' ' +
                                    ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                    ' -Jthreads=4 ' +
                                    ' -JrequestsCount=' + requests_count + ' ' +
                                    ' -JpreconditionsPath=' + dataFolder + 'preconditions.json' +
                                    ' -JservicesFolder=' + dataFolder + ' ' +
                                    ' -JservicesGroup=srids ' +
                                    ' -JservicesCount=' + srids_count + ' ' +
                                    ' -JbatchSize=500 ' +
                                    ' -JreferenceId=usageRegrTestR' + currentBuild.number + ' ' +
                                    ' -Jmode=FAIL_ON_EXISTING ' +
                                    ' -JstartTime=2021-01-02 ' +
                                    ' -JendTime=2021-01-25 ' +
                                    ' -JlogSendingEachRequestNumber=500 ' +
                                    ' -JwaitTimeoutOfLastCallback=1800 ' +
                                    ' -JbapiCount=4 '  +
                                    ' -JbuildId=' + build_id + ' ' +
                                    ' -JtestCaseId=29 ' +
                                    ' -Jticket=' + ticket + ' ' +
                                    ' -JmetricsIntervalMS=60000 '

                                def resultsFilePath = resultsFolder + '/results.json'
                                if (fileExists(resultsFilePath)) {
                                    results = readFile(resultsFilePath)
                                    def resultsJson = readJSON text: results
                                    testRunUUID = resultsJson.run_uuid
                                }

                                def preconditionsFilePath = resultsFolder + '/preconditions.json'
                                if (fileExists(preconditionsFilePath)) {
                                    preconditions = readFile(preconditionsFilePath)
                                }
                            }

                            if (results) {
                                writeFile(file: 'results_main.json', text: results)
                                results = null
                            }

                            if (preconditions) {
                                writeFile(file: 'preconditions_main.json', text: preconditions)
                                preconditions = null
                            }

                            dbSizeAfter = getDBSize()
                            echo "db size after: ${dbSizeAfter} M"

                            def diff = dbSizeAfter.toLong() - dbSizeBefore.toLong()
                            echo "db size changes: ${diff} M"
                        } else {
                            echo 'Usage Regression Test was skipped because the precondtions were failed'
                        }
                    } else {
                        echo 'Usage Test(main variant) is skipped'
                    }
                }

                stage ('Collect infrastructure metrics') {
                    collectInfrastructureMetrics(testRunUUID)
                }

                testRunUUID = null

                stage('Run Usage Regression Test(variant #1)') {
                    if(params['run_main_variant']) {
                        if(params['run_variant_1']) {
                            if (arePreconditionsDone) {
                                dbSizeBefore = getDBSize()
                                echo "db size before: ${dbSizeBefore} M"

                                def results = null
                                def preconditions = null

                                node('util-box-2-perm-slave') {
                                    def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                                     '/configs/general_configs.properties'
                                    def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                                    def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.scripts'] + '/regression/usage_test.jmx'
                                    def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.results'] + '/regression/usage_test/variant1/run' +
                                     currentBuild.number
                                    def dataFolder = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.results'] + '/regression/usage_preconditions/run' +
                                     currentBuild.number + '/'
                                    def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.configs.env'] + '/bigfoot.properties'
                                    def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                     configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                                    sh jmeterShPath +
                                        ' -t ' + scriptPath +
                                        ' -j ' + resultsFolder + '/all.log' +
                                        ' -l ' + resultsFolder + '/results.jtl' +
                                        ' -JresultsFolder=' + resultsFolder + '/ ' +
                                        ' -Jenv_properties_file=' + envConfigs + ' ' +
                                        ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                        ' -Jthreads=40 ' +
                                        ' -JrequestsCount=' + requests_count + ' ' +
                                        ' -JpreconditionsPath=' + dataFolder + 'preconditions.json' +
                                        ' -JservicesFolder=' + dataFolder + ' ' +
                                        ' -JservicesGroup=srids ' +
                                        ' -JservicesCount=' + srids_count + ' ' +
                                        ' -JbatchSize=500 ' +
                                        ' -JreferenceId=usageRegrTestR' + currentBuild.number + ' ' +
                                        ' -Jmode=OVERWRITE_ON_EXISTING ' +
                                        ' -JstartTime=2021-01-02 ' +
                                        ' -JendTime=2021-01-25 ' +
                                        ' -JlogSendingEachRequestNumber=100 ' +
                                        ' -JwaitTimeoutOfLastCallback=600 ' +
                                        ' -JbapiCount=4' +
                                        ' -JbuildId=' + build_id + ' ' +
                                        ' -JtestCaseId=30 ' +
                                        ' -Jticket=' + ticket + ' ' +
                                        ' -JmetricsIntervalMS=60000 '

                                    def resultsFilePath = resultsFolder + '/results.json'
                                    if (fileExists(resultsFilePath)) {
                                        results = readFile(resultsFilePath)
                                        def resultsJson = readJSON text: results
                                        testRunUUID = resultsJson.run_uuid
                                    }

                                    def preconditionsFilePath = resultsFolder + '/preconditions.json'
                                    if (fileExists(preconditionsFilePath)) {
                                        preconditions = readFile(preconditionsFilePath)
                                    }
                                }

                                if (results) {
                                    writeFile(file: 'results_variant_1.json', text: results)
                                    results = null
                                }

                                if (preconditions) {
                                    writeFile(file: 'preconditions_variant_1.json', text: preconditions)
                                    preconditions = null
                                }

                                dbSizeAfter = getDBSize()
                                echo "db size after: ${dbSizeAfter} M"

                                def diff = dbSizeAfter.toLong() - dbSizeBefore.toLong()
                                echo "db size changes: ${diff} M"
                            } else {
                                echo 'Usage Regression Test was skipped because the precondtions were failed'
                            }
                        } else {
                            echo 'Usage Test(variant #1) is skipped'
                        }
                    } else {
                        echo 'Usage Test(variant #1) can\'t be run without Usage Test(main variant)'
                    }
                }

                stage ('Collect infrastructure metrics') {
                    collectInfrastructureMetrics(testRunUUID)
                }

                arePreconditionsDone = false
                stage('Create preconditions(variant #3)') {
                    if(params['run_variant_3']) {
                        def failedRequests = null
                        node('util-box-2-perm-slave') {
                            def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                             '/configs/general_configs.properties'
                            def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                            def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.scripts'] + '/regression/usage_preconditions.jmx'
                            def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.results'] + '/regression/usage_preconditions/variant3/run' +
                             currentBuild.number
                            def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.env'] + '/bigfoot.properties'
                            def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                            def shStatusCode = sh script: jmeterShPath +
                                ' -t ' + scriptPath +
                                ' -j ' + resultsFolder + '/all.log' +
                                ' -l ' + resultsFolder + '/results.jtl' +
                                ' -JresultsFolder=' + resultsFolder + '/ ' +
                                ' -Jenv_properties_file=' + envConfigs + ' ' +
                                ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                ' -Jthreads=40 ' +
                                ' -JrequestsCount=' + srids_count + ' ' +
                                ' -JlogCurrentStatusRequestNumber=100 ' +
                                ' -Jprefix=usageRergTest ' +
                                ' -JstartDate=2019-01-01 ' +
                                ' -JbillCycleType=monthly ' +
                                ' -Juom=SECOND ' +
                                ' -JaccountCategoryId=' + getGeneralTestParams().accountCategoryId + ' ' +
                                ' -JpaymentTermId=' + getGeneralTestParams().paymentTermId + ' ' +
                                ' -JsrCategoryId=' + getGeneralTestParams().srCategoryId + ' ' +
                                ' -JproductCategoryId=' + getGeneralTestParams().productCategoryId + ' ' +
                                ' -JchargeCategoryId=' + getGeneralTestParams().chargeCategoryId + ' ' +
                                ' -JusageChargeCategoryId=' + getGeneralTestParams().usageChargeCategoryId + ' ' +
                                ' -JisWithRateTable=false ' +
                                ' -JruleLimit=250 ' +
                                ' -JlimitedRulesCount=1',
                                returnStatus:true

                            def failedRequestsFilePath = resultsFolder + '/failed_requests.txt'
                            if (fileExists(failedRequestsFilePath)) {
                                failedRequests = readFile(failedRequestsFilePath)
                                arePreconditionsDone = true
                            }
                        }

                        if (failedRequests) {
                            writeFile(file: 'preconditions_variant_3_failed_requests.txt', text: failedRequests)
                            failedRequests = null
                        }
                    } else {
                        echo 'Usage Test(variant #3) is skipped'
                    }
                }

                testRunUUID = null

                stage('Run Usage Regression Test(variant #3)') {
                    if(params['run_variant_3']) {
                        if (arePreconditionsDone) {
                            dbSizeBefore = getDBSize()
                            echo "db size before: ${dbSizeBefore} M"

                            def results = null
                            def preconditions = null
                            node('util-box-2-perm-slave') {
                                def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                                 '/configs/general_configs.properties'
                                def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                                def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.scripts'] + '/regression/usage_test.jmx'
                                def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_test/variant3/run' + currentBuild.number
                                def dataFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_preconditions/variant3/run' +
                                 currentBuild.number + '/'
                                def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.env'] + '/bigfoot.properties'
                                def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                                sh jmeterShPath +
                                    ' -t ' + scriptPath +
                                    ' -j ' + resultsFolder + '/all.log' +
                                    ' -l ' + resultsFolder + '/results.jtl' +
                                    ' -JresultsFolder=' + resultsFolder + '/ ' +
                                    ' -Jenv_properties_file=' + envConfigs + ' ' +
                                    ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                    ' -Jthreads=40 ' +
                                    ' -JrequestsCount=' + requests_count + ' ' +
                                    ' -JpreconditionsPath=' + dataFolder + 'preconditions.json' +
                                    ' -JservicesFolder=' + dataFolder + ' ' +
                                    ' -JservicesGroup=srids ' +
                                    ' -JservicesCount=' + srids_count + ' ' +
                                    ' -JbatchSize=500 ' +
                                    ' -JreferenceId=usageRegrTestV3R' + currentBuild.number + ' ' +
                                    ' -Jmode=FAIL_ON_EXISTING ' +
                                    ' -JstartTime=2021-01-02 ' +
                                    ' -JendTime=2021-01-25 ' +
                                    ' -JlogSendingEachRequestNumber=100 ' +
                                    ' -JwaitTimeoutOfLastCallback=600 ' +
                                    ' -JbapiCount=4 ' +
                                    ' -JbuildId=' + build_id + ' ' +
                                    ' -JtestCaseId=34 ' +
                                    ' -Jticket=' + ticket + ' ' +
                                    ' -JmetricsIntervalMS=60000 '

                                def resultsFilePath = resultsFolder + '/results.json'
                                if (fileExists(resultsFilePath)) {
                                    results = readFile(resultsFilePath)
                                    def resultsJson = readJSON text: results
                                    testRunUUID = resultsJson.run_uuid
                                }

                                def preconditionsFilePath = resultsFolder + '/preconditions.json'
                                if (fileExists(preconditionsFilePath)) {
                                    preconditions = readFile(preconditionsFilePath)
                                }
                            }

                            if (results) {
                                writeFile(file: 'results_variant_3.json', text: results)
                                results = null
                            }

                            if (preconditions) {
                                writeFile(file: 'preconditions_variant_3.json', text: preconditions)
                                preconditions = null
                            }

                            dbSizeAfter = getDBSize()
                            echo "db size after: ${dbSizeAfter} M"

                            def diff = dbSizeAfter.toLong() - dbSizeBefore.toLong()
                            echo "db size changes: ${diff} M"
                        } else {
                            echo 'Usage Regression Test was skipped because the precondtions were failed'
                        }
                    } else {
                        echo 'Usage Test(variant #3) is skipped'
                    }
                }

                stage ('Collect infrastructure metrics') {
                    collectInfrastructureMetrics(testRunUUID)
                }

                arePreconditionsDone = false
                stage('Create preconditions(variant #4)') {
                    if(params['run_variant_4']) {
                        def failedRequests = null
                        node('util-box-2-perm-slave') {
                            def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                             '/configs/general_configs.properties'
                            def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                            def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.scripts'] + '/regression/usage_preconditions.jmx'
                            def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.results'] + '/regression/usage_preconditions/variant4/run' +
                             currentBuild.number
                            def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.env'] + '/bigfoot.properties'
                            def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                            def shStatusCode = sh script: jmeterShPath +
                                ' -t ' + scriptPath +
                                ' -j ' + resultsFolder + '/all.log' +
                                ' -l ' + resultsFolder + '/results.jtl' +
                                ' -JresultsFolder=' + resultsFolder + '/ ' +
                                ' -Jenv_properties_file=' + envConfigs + ' ' +
                                ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                ' -Jthreads=40 ' +
                                ' -JrequestsCount=' + srids_count + ' ' +
                                ' -JlogCurrentStatusRequestNumber=100 ' +
                                ' -Jprefix=usageRergTest ' +
                                ' -JstartDate=2019-01-01 ' +
                                ' -JbillCycleType=monthly ' +
                                ' -Juom=SECOND ' +
                                ' -JaccountCategoryId=' + getGeneralTestParams().accountCategoryId + ' ' +
                                ' -JpaymentTermId=' + getGeneralTestParams().paymentTermId + ' ' +
                                ' -JsrCategoryId=' + getGeneralTestParams().srCategoryId + ' ' +
                                ' -JproductCategoryId=' + getGeneralTestParams().productCategoryId + ' ' +
                                ' -JchargeCategoryId=' + getGeneralTestParams().chargeCategoryId + ' ' +
                                ' -JusageChargeCategoryId=' + getGeneralTestParams().usageChargeCategoryId + ' ' +
                                ' -JisWithRateTable=false ' +
                                ' -JruleLimit=5 ' +
                                ' -JlimitedRulesCount=84',
                                returnStatus:true

                            def failedRequestsFilePath = resultsFolder + '/failed_requests.txt'
                            if (fileExists(failedRequestsFilePath)) {
                                failedRequests = readFile(failedRequestsFilePath)
                                arePreconditionsDone = true
                            }
                        }

                        if (failedRequests) {
                            writeFile(file: 'preconditions_variant_4_failed_requests.txt', text: failedRequests)
                            failedRequests = null
                        }
                    } else {
                        echo 'Usage Test(variant #4) is skipped'
                    }
                }

                testRunUUID = null

                stage('Run Usage Regression Test(variant #4)') {
                    if(params['run_variant_4']) {
                        if (arePreconditionsDone) {
                            dbSizeBefore = getDBSize()
                            echo "db size before: ${dbSizeBefore} M"

                            def results = null
                            def preconditions = null
                            node('util-box-2-perm-slave') {
                                def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                                 '/configs/general_configs.properties'
                                def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                                def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.scripts'] + '/regression/usage_test.jmx'
                                def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_test/variant4/run' +
                                 currentBuild.number
                                def dataFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_preconditions/variant4/run' +
                                 currentBuild.number + '/'
                                def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.env'] + '/bigfoot.properties'
                                def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                                sh jmeterShPath +
                                    ' -t ' + scriptPath +
                                    ' -j ' + resultsFolder + '/all.log' +
                                    ' -l ' + resultsFolder + '/results.jtl' +
                                    ' -JresultsFolder=' + resultsFolder + '/ ' +
                                    ' -Jenv_properties_file=' + envConfigs + ' ' +
                                    ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                    ' -Jthreads=40 ' +
                                    ' -JrequestsCount=' + requests_count + ' ' +
                                    ' -JpreconditionsPath=' + dataFolder + 'preconditions.json' +
                                    ' -JservicesFolder=' + dataFolder + ' ' +
                                    ' -JservicesGroup=srids ' +
                                    ' -JservicesCount=' + srids_count + ' ' +
                                    ' -JbatchSize=500 ' +
                                    ' -JreferenceId=usageRegrTestV4R' + currentBuild.number + ' ' +
                                    ' -Jmode=FAIL_ON_EXISTING ' +
                                    ' -JstartTime=2021-01-02 ' +
                                    ' -JendTime=2021-01-25 ' +
                                    ' -JlogSendingEachRequestNumber=100 ' +
                                    ' -JwaitTimeoutOfLastCallback=600 ' +
                                    ' -JbapiCount=4 ' +
                                    ' -JbuildId=' + build_id + ' ' +
                                    ' -JtestCaseId=35 ' +
                                    ' -Jticket=' + ticket + ' ' +
                                    ' -JmetricsIntervalMS=60000 '

                                def resultsFilePath = resultsFolder + '/results.json'
                                if (fileExists(resultsFilePath)) {
                                    results = readFile(resultsFilePath)
                                    def resultsJson = readJSON text: results
                                    testRunUUID = resultsJson.run_uuid
                                }

                                def preconditionsFilePath = resultsFolder + '/preconditions.json'
                                if (fileExists(preconditionsFilePath)) {
                                    preconditions = readFile(preconditionsFilePath)
                                }
                            }

                            if (results) {
                                writeFile(file: 'results_variant_4.json', text: results)
                                results = null
                            }

                            if (preconditions) {
                                writeFile(file: 'preconditions_variant_4.json', text: preconditions)
                                preconditions = null
                            }

                            dbSizeAfter = getDBSize()
                            echo "db size after: ${dbSizeAfter} M"

                            def diff = dbSizeAfter.toLong() - dbSizeBefore.toLong()
                            echo "db size changes: ${diff} M"
                        } else {
                            echo 'Usage Regression Test was skipped because the precondtions were failed'
                        }
                    } else {
                        echo 'Usage Test(variant #4) is skipped'
                    }
                }

                stage ('Collect infrastructure metrics') {
                    collectInfrastructureMetrics(testRunUUID)
                }

                arePreconditionsDone = false
                stage('Create preconditions(variant #5)') {
                    if(params['run_variant_5']) {
                        def failedRequests = null
                        node('util-box-2-perm-slave') {
                            def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                             '/configs/general_configs.properties'
                            def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                            def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.scripts'] + '/regression/usage_preconditions.jmx'
                            def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.results'] + '/regression/usage_preconditions/variant5/run' +
                              currentBuild.number
                            def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.env'] + '/bigfoot.properties'
                            def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' +
                             configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'

                            def shStatusCode = sh script: jmeterShPath +
                                ' -t ' + scriptPath +
                                ' -j ' + resultsFolder + '/all.log' +
                                ' -l ' + resultsFolder + '/results.jtl' +
                                ' -JresultsFolder=' + resultsFolder + '/ ' +
                                ' -Jenv_properties_file=' + envConfigs + ' ' +
                                ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
                                ' -Jthreads=40 ' +
                                ' -JrequestsCount=' + srids_count + ' ' +
                                ' -JlogCurrentStatusRequestNumber=100 ' +
                                ' -Jprefix=usageRergTest ' +
                                ' -JstartDate=2019-01-01 ' +
                                ' -JbillCycleType=monthly ' +
                                ' -Juom=SECOND ' +
                                ' -JaccountCategoryId=' + getGeneralTestParams().accountCategoryId + ' ' +
                                ' -JpaymentTermId=' + getGeneralTestParams().paymentTermId + ' ' +
                                ' -JsrCategoryId=' + getGeneralTestParams().srCategoryId + ' ' +
                                ' -JproductCategoryId=' + getGeneralTestParams().productCategoryId + ' ' +
                                ' -JchargeCategoryId=' + getGeneralTestParams().chargeCategoryId + ' ' +
                                ' -JusageChargeCategoryId=' + getGeneralTestParams().usageChargeCategoryId + ' ' +
                                ' -JisWithRateTable=true ' +
                                ' -JruleLimit=250 ' +
                                ' -JlimitedRulesCount=1',
                                returnStatus:true

                            def failedRequestsFilePath = resultsFolder + '/failed_requests.txt'
                            if (fileExists(failedRequestsFilePath)) {
                                failedRequests = readFile(failedRequestsFilePath)
                                arePreconditionsDone = true
                            }
                        }

                        if (failedRequests) {
                            writeFile(file: 'preconditions_failed_requests.txt', text: failedRequests)
                            failedRequests = null
                        }
                    } else {
                        echo 'Usage Test(variant #5) is skipped'
                    }
                }

                testRunUUID = null

                stage('Run Usage Regression Test(variant #5)') {
                    if(params['run_variant_5']) {
                        if (arePreconditionsDone) {
                            dbSizeBefore = getDBSize()
                            echo "db size before: ${dbSizeBefore} M"

                            def results = null
                            def preconditions = null
                            node('util-box-2-perm-slave') {
                                def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
                                 '/configs/general_configs.properties'
                                def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.jmeter'] + '/bin/jmeter.sh -n'
                                def scriptPath = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.scripts'] + '/regression/usage_test_3users.jmx'
                                def resultsFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_test/variant5/run' +
                                 currentBuild.number
                                def dataFolder = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.results'] + '/regression/usage_preconditions/variant5/run' +
                                 currentBuild.number + '/'
                                def envConfigs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.env'] + '/bigfoot.properties'
                                def tenant1Configs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression.properties'
                                def tenant2Configs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression_user2.properties'
                                def tenant3Configs = getGeneralTestParams().scriptsFolder + '/' +
                                 configsProps['folder.configs.tenant'] + '/bigfoot/tenant_9_regression_user3.properties'

                                sh jmeterShPath +
                                    ' -t ' + scriptPath +
                                    ' -j ' + resultsFolder + '/all.log' +
                                    ' -l ' + resultsFolder + '/results.jtl' +
                                    ' -JresultsFolder=' + resultsFolder + '/ ' +
                                    ' -Jenv_properties_file=' + envConfigs + ' ' +
                                    ' -Jtenant_1_properties_file=' + tenant1Configs + ' ' +
                                    ' -Jtenant_2_properties_file=' + tenant2Configs + ' ' +
                                    ' -Jtenant_3_properties_file=' + tenant3Configs + ' ' +
                                    ' -Jthreads=40 ' +
                                    ' -JrequestsCount=' + requests_count + ' ' +
                                    ' -JpreconditionsPath=' + dataFolder + 'preconditions.json' +
                                    ' -JservicesFolder=' + dataFolder + ' ' +
                                    ' -JservicesGroup=srids ' +
                                    ' -JservicesCount=' + srids_count + ' ' +
                                    ' -JbatchSize=500 ' +
                                    ' -JreferenceId=usageRegrTestR' + currentBuild.number + ' ' +
                                    ' -Jmode=FAIL_ON_EXISTING ' +
                                    ' -JstartTime=2021-01-02 ' +
                                    ' -JendTime=2021-01-25 ' +
                                    ' -JlogSendingEachRequestNumber=100 ' +
                                    ' -JwaitTimeoutOfLastCallback=600 ' +
                                    ' -JbapiCount=4 ' +
                                    ' -JbuildId=' + build_id + ' ' +
                                    ' -JtestCaseId=36 ' +
                                    ' -Jticket=' + ticket + ' ' +
                                    ' -JmetricsIntervalMS=60000 '

                                def resultsFilePath = resultsFolder + '/results.json'
                                if (fileExists(resultsFilePath)) {
                                    results = readFile(resultsFilePath)
                                    def resultsJson = readJSON text: results
                                    testRunUUID = resultsJson.run_uuid
                                }

                                def preconditionsFilePath = resultsFolder + '/preconditions.json'
                                if (fileExists(preconditionsFilePath)) {
                                    preconditions = readFile(preconditionsFilePath)
                                }
                            }

                            if (results) {
                                writeFile(file: 'results_main.json', text: results)
                                results = null
                            }

                            if (preconditions) {
                                writeFile(file: 'preconditions_main.json', text: preconditions)
                                preconditions = null
                            }

                            dbSizeAfter = getDBSize()
                            echo "db size after: ${dbSizeAfter} M"

                            def diff = dbSizeAfter.toLong() - dbSizeBefore.toLong()
                            echo "db size changes: ${diff} M"
                        } else {
                            echo 'Usage Regression Test was skipped because the precondtions were failed'
                        }
                    } else {
                        echo 'Usage Test(variant #5) is skipped'
                    }
                }

                stage ('Collect infrastructure metrics') {
                    collectInfrastructureMetrics(testRunUUID)
                }

            } finally {
                stage('save instance\'s properties') {
                    for (int i = 0; i < getGeneralTestParams().bapiCount; i++) {
                        sshagent(credentials: ['deployer']) {
                            String bapiUrl = String.format(getGeneralTestParams().bapiUrlTemplate, i+1)
                            def remoteFile = '/opt/billing_api/conf/billing.properties'
                            def localFile = "configs/bapi_${i+1}_billing.properties"

                            try {
                                sh """
                                    ssh -o StrictHostKeyChecking=no -i /path/to/private/key -l deployer '${bapiUrl}' \
                                    "sudo cat '${remoteFile}' | sed 's/\\(.*pass[^=]*=\\)\\(.*\\)/\\1****/g'" \
                                    > '${localFile}'
                                """
                            } catch (Exception ex) {
                                println "Error: ${ex.getMessage()}"
                            }
                        }
                    }
                }
                stage('archive results') {
                    try {
                        def archiveFileTypes = '*.xml, *.properties, *.txt, *.jtl, *.zip, *.json'
                        def archiveDir = 'configs'
                        def archiveName = 'configs.zip'

                        sh 'ls -la'
                        zip(zipFile: archiveName, dir: archiveDir)
                        archiveArtifacts(artifacts: archiveFileTypes)
                    } catch (Exception ex) {
                        println "Error: ${ex.getMessage()}"
                    }
                }
            }
        }
    }
}

def createPreconditionsMain() {
    def failedRequests = null
    node('util-box-2-perm-slave') {
        def configsProps = readProperties  file: getGeneralTestParams().scriptsFolder +
         '/configs/general_configs.properties'
        def jmeterShPath = getGeneralTestParams().scriptsFolder + '/' + configsProps['folder.jmeter'] +
         '/bin/jmeter.sh -n'
        def scriptPath = getGeneralTestParams().scriptsFolder + '/' + configsProps['folder.scripts'] +
         '/regression/usage_preconditions.jmx'
        def resultsFolder = getGeneralTestParams().scriptsFolder + '/' + configsProps['folder.results'] +
         '/regression/usage_preconditions/run' + currentBuild.number
        def envConfigs = getGeneralTestParams().scriptsFolder + '/' + configsProps['folder.configs.env'] +
         '/bigfoot.properties'
        def tenantConfigs = getGeneralTestParams().scriptsFolder + '/' + configsProps['folder.configs.tenant'] +
         '/bigfoot/tenant_9_regression.properties'

        def shStatusCode = sh script: jmeterShPath +
            ' -t ' + scriptPath +
            ' -j ' + resultsFolder + '/all.log' +
            ' -l ' + resultsFolder + '/results.jtl' +
            ' -JresultsFolder=' + resultsFolder + '/ ' +
            ' -Jenv_properties_file=' + envConfigs + ' ' +
            ' -Jtenant_properties_file=' + tenantConfigs + ' ' +
            ' -Jthreads=40 ' +
            ' -JrequestsCount=' + srids_count + ' ' +
            ' -JlogCurrentStatusRequestNumber=100 ' +
            ' -Jprefix=usageRergTest ' +
            ' -JstartDate=2020-01-01 ' +
            ' -JbillCycleType=monthly ' +
            ' -Juom=SECOND ' +
            ' -JaccountCategoryId=' + getGeneralTestParams().accountCategoryId + ' ' +
            ' -JpaymentTermId=' + getGeneralTestParams().paymentTermId + ' ' +
            ' -JsrCategoryId=' + getGeneralTestParams().srCategoryId + ' ' +
            ' -JproductCategoryId=' + getGeneralTestParams().productCategoryId + ' ' +
            ' -JchargeCategoryId=' + getGeneralTestParams().chargeCategoryId + ' ' +
            ' -JusageChargeCategoryId=' + getGeneralTestParams().usageChargeCategoryId + ' ' +
            ' -JisWithRateTable=true ' +
            ' -JruleLimit=250 ' +
            ' -JlimitedRulesCount=1',
            returnStatus:true

        def failedRequestsFilePath = resultsFolder + '/failed_requests.txt'
        if (fileExists(failedRequestsFilePath)) {
            failedRequests = readFile(failedRequestsFilePath)
            arePreconditionsDone = true
        }
    }

    if (failedRequests) {
        writeFile(file: 'preconditions_failed_requests.txt', text: failedRequests)
        failedRequests = null
    }
}

def collectInfrastructureMetrics(String testRunUUID) {
    if (!testRunUUID) {
        try {
            build job: 'bigfoot-test_scripts-collect_infrastructure_metrics', parameters: [
                string(name: 'run_uuid', value: "${testRunUUID}"),
                string(name: 'test_name', value: 'UsageRegressionTest')
            ]
        } catch (Exception ex) {
            echo "Error collecting infrastructure metrics: ${ex.getMessage()}"
        }
    } else {
        echo 'Skipping infrastructure metrics collection because testRunUUID is null'
    }
}

def isServiceActive(String url, String service) {
    if (!url || !service) {
        throw new IllegalArgumentException("url and service parameters cannot be null or empty")
    }
    sshagent (credentials: ['deployer']) {
        def cmd = "ssh -o StrictHostKeyChecking=no -l deployer ${url} \"systemctl is-active ${service}\""
        try {
            def status = sh(returnStdout: true, script: cmd).trim()
            return status == 'active'
        } catch (Exception ex) {
            echo "Error executing command: ${ex.getMessage()}"
            return false
        }
    }
}

def getDBSize() {
    def dbUrl = getGeneralTestParams().dbUrl
    def dbUser = getGeneralTestParams().dbUser
    def dbPassword = getGeneralTestParams().dbPassword

    // Use a more targeted command to retrieve the database size
    def cmd = "sshpass -p ${dbPassword} ssh -o StrictHostKeyChecking=no -l ${dbUser} ${dbUrl} du -sh /var/lib/mysql"

    try {
        def dbSize = sh(returnStdout: true, script: cmd).trim()
        return dbSize
    } catch (Exception ex) {
        echo "Error executing command: ${ex.getMessage()}"
        return "Unknown"
    }
}
