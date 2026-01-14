// Mapping Templates Library
const MappingTemplates = {
    echo: {
        id: 'echo-',
        pattern: '^ECHO.*',
        response: '{{message}}',
        priority: 10,
        delimiter: '\\n',
        description: 'Echo back the received message'
    },
    jsonApi: {
        id: 'json-api-',
        pattern: '^\\{.*\\}$',
        response: '{"status":"success","timestamp":"{{now}}","data":{{message}}}',
        priority: 20,
        delimiter: '\\n',
        description: 'JSON API mock response',
        advancedMatching: {
            jsonPath: { expression: '$.type', equalTo: 'request' }
        }
    },
    soap: {
        id: 'soap-',
        pattern: '^<.*>.*</.*>$',
        response: '<response><status>success</status><data>{{request.xmlPath \'data\'}}</data></response>',
        priority: 20,
        delimiter: '\\n',
        description: 'SOAP service mock',
        advancedMatching: {
            xmlPath: { expression: 'operation', equalTo: 'GetData' }
        }
    },
    iso8583: {
        id: 'iso8583-',
        pattern: '^(0100|0200|0400|0800)[0-9A-Fa-f]+$',
        response: '0110{{transaction_data}}00',
        priority: 25,
        delimiter: '',
        description: 'ISO-8583 payment message'
    },
    faultReset: {
        id: 'fault-reset-',
        pattern: '^FAULT_RESET.*',
        response: '{{fault:CONNECTION_RESET}}',
        priority: 15,
        delimiter: '\\n',
        description: 'Connection reset fault injection'
    },
    faultEmpty: {
        id: 'fault-empty-',
        pattern: '^FAULT_EMPTY.*',
        response: '{{fault:EMPTY_RESPONSE}}',
        priority: 15,
        delimiter: '\\n',
        description: 'Empty response fault injection'
    },
    proxy: {
        id: 'proxy-',
        pattern: '^PROXY_.*',
        response: '{{proxy:backend-server:8080}}',
        priority: 5,
        delimiter: '\\n',
        description: 'Proxy to real backend system'
    },
    delayed: {
        id: 'delayed-',
        pattern: '^SLOW_.*',
        response: 'DELAYED_RESPONSE',
        priority: 10,
        delimiter: '\\n',
        fixedDelayMs: 1000,
        description: 'Simulated slow response (1 second)'
    },
    scenario: {
        id: 'scenario-',
        pattern: '^AUTH_.*',
        response: '{"status":"authorized","token":"{{uuid}}"}',
        priority: 20,
        delimiter: '\\n',
        scenarioName: 'auth-flow',
        requiredState: 'Started',
        newState: 'Authorized',
        description: 'Stateful authentication scenario'
    }
};

const TemplateCategories = {
    basic: ['echo'],
    api: ['jsonApi', 'soap'],
    financial: ['iso8583'],
    testing: ['faultReset', 'faultEmpty', 'delayed'],
    advanced: ['proxy', 'scenario']
};
