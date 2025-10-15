package sybrix.easygsp2.models

class ClaudeAPI {
        String description
        String method
        String protocol = "https"
        String path
        
        List<ClaudeAPIParameter> parameters
        List<ClaudeAPIParameter> queryStringParameters
        List<ClaudeAPIParameter> pathParameters
        Object body
        //String returnObject
        Object returnType  // Can be a String for simple types or a Map/Object for complex structures
        Boolean authenticateRequired
        Boolean hasCircularReferences = false  // Indicates if the return type contains circular references
        
        public  ClaudeAPI(){
                parameters = new ArrayList<>()
                queryStringParameters = new ArrayList<>()
                pathParameters = new ArrayList<>()
        }
}


class ClaudeAPIParameter {
        String name
        String parameter
        Boolean required = false
        String type
        String defaultValue
        String description
}

