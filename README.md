# easygsp-framework

Because I really hate Spring and really like Groovy
It's a great product but Roo and Boot both just added  more annotations and confusion.

Spring supports groovy so you can write a controller that looks almost exactly like this Spring, but you can't escape Spring's annotations.  It's so many...so so many.  


####How is it different?
Quite honestly, from a dev's perspective, it's not that different.  EasyGsp is designed for use with Groovy. 
and has smaller ambitions than Spring. Spring inspired this framework but from this point on, let's stop talking about Spring.

As of now, EasyGsp supports 2 annotations.  @Api and @Secured
I'm sure more will come but as of now that's it.  

Here's a controller in EasyGsp:  
```groovy
def ProfileController {
    def get(id){
        def db = SqlConnection(...)
        // do some database stuff
        new Profile(username:db.username, lastname:db.lastname)
    }
    
    def post(ProfileRequest profileRequest){
            
    }
    
    def post(ProfileRequest profileRequest){
          "forward:/api/images?q=a"   
    }
    
    def post(ProfileRequest profileRequest){
            new ControllerResponse(forwarded:true, url:'/url/image')   
    }
}
```





