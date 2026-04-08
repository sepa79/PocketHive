// Mapping Templates Library
const MappingTemplates = {
    echo: {
        id: 'echo-',
        pattern: '^ECHO.*',
        response: '{{message}}',
        priority: 10,
        wireProfile: 'LINE',
        delimiter: '\\n',
        description: 'Echo back the received message'
    },
    jsonApi: {
        id: 'json-api-',
        pattern: '^\\{.*\\}$',
        response: '{"status":"success","timestamp":"{{now}}","data":{{message}}}',
        priority: 20,
        wireProfile: 'LINE',
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
        wireProfile: 'LINE',
        delimiter: '\\n',
        description: 'SOAP service mock',
        advancedMatching: {
            xmlPath: { expression: 'operation', equalTo: 'GetData' }
        }
    },
    iso8583: {
        id: 'iso8583-',
        pattern: '^0200.*',
        response: '0210{{message:4}}00',
        priority: 100,
        wireProfile: 'LENGTH_PREFIX_2B',
        delimiter: '',
        description: 'ISO-8583 financial message (2-byte length prefix)'
    },
    stxEtx: {
        id: 'stx-etx-',
        pattern: '.*',
        response: '\u0002RESPONSE\u0003',
        priority: 50,
        wireProfile: 'STX_ETX',
        delimiter: '',
        description: 'STX/ETX binary framing (0x02...0x03)'
    },
    xmlDocument: {
        id: 'xml-doc-',
        pattern: '.*<RequestBody>.*',
        response: '<?xml version="1.0"?><Document><ResponseBody>OK</ResponseBody></Document>',
        priority: 50,
        wireProfile: 'DELIMITER',
        requestDelimiter: '</Document>',
        delimiter: '',
        description: 'Multi-line XML document protocol'
    },
    faultReset: {
        id: 'fault-reset-',
        pattern: '^FAULT_RESET.*',
        response: '{{fault:CONNECTION_RESET}}',
        priority: 15,
        wireProfile: 'LINE',
        delimiter: '\\n',
        description: 'Connection reset fault injection'
    },
    faultEmpty: {
        id: 'fault-empty-',
        pattern: '^FAULT_EMPTY.*',
        response: '{{fault:EMPTY_RESPONSE}}',
        priority: 15,
        wireProfile: 'LINE',
        delimiter: '\\n',
        description: 'Empty response fault injection'
    },
    proxy: {
        id: 'proxy-',
        pattern: '^PROXY_.*',
        response: '{{proxy:backend-server:8080}}',
        priority: 5,
        wireProfile: 'LINE',
        delimiter: '\\n',
        description: 'Proxy to real backend system'
    },
    delayed: {
        id: 'delayed-',
        pattern: '^SLOW_.*',
        response: 'DELAYED_RESPONSE',
        priority: 10,
        wireProfile: 'LINE',
        delimiter: '\\n',
        fixedDelayMs: 1000,
        description: 'Simulated slow response (1 second)'
    },
    scenario: {
        id: 'scenario-',
        pattern: '^AUTH_.*',
        response: '{"status":"authorized","token":"{{uuid}}"}',
        priority: 20,
        wireProfile: 'LINE',
        delimiter: '\\n',
        scenarioName: 'auth-flow',
        requiredState: 'Started',
        newState: 'Authorized',
        description: 'Stateful authentication scenario'
    }
};

const TemplateCategories = {
    basic: ['echo'],
    api: ['jsonApi', 'soap', 'xmlDocument'],
    financial: ['iso8583'],
    binary: ['stxEtx'],
    testing: ['faultReset', 'faultEmpty', 'delayed'],
    advanced: ['proxy', 'scenario']
};
