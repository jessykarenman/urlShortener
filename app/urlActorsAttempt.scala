import scala.math._
import java.sql.{Connection, DriverManager, ResultSet}
import akka.actor._
import akka.routing.RoundRobinRouter
import scala.concurrent.Await
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.Await;
import scala.concurrent.Promise;
import akka.util.Timeout;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;
import scala.concurrent.ExecutionContext.Implicits.global
import akka.agent.Agent
import akka.actor.PoisonPill 

//case class invalidUrlException(message: String) extends Exception(message)
//case class invalidHashException(message: String) extends Exception(message)

case object Create

class urlActorsAttempt {
    
    val base62Alphabet = Array('0', '1', '2', '3', '4', '5', '6', '7', '8', '9','A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z')
    
    val system = ActorSystem("urlSystem")
    
    /**
     * Storage maps 
     */
    // Key:Url -> Value: Id
    val urlIdMap = new scala.collection.mutable.HashMap[String, Int]
    // Key:Hash -> Value: ClickCount
    val hashClicksMap = new scala.collection.mutable.HashMap[String,Int]
    // Key:Hash -> Value:Url
    val hashUrlMap = new scala.collection.mutable.HashMap[String,String]
    
    // incremental ID for created hash's
    private var id = 0;
    
    /** 
     * Given a url a base 62 hash is returned
     * 
     * @Param a URL
     * @Return a hash: String
     */
    def hashUrl(url: String): String = { 
        if(!validUrl(url)){
            throw invalidUrlException(s"That is not a valid URL.")
        }
        val prefixUrl = attachPrefix(url)
        var idNum = urlIdMap.getOrElse(prefixUrl,-1)
        if(idNum == -1){
            create(url)    
        }else{
            convertTo62(idNum)
        }
    }
    
    /**
     * Retrieve URL associated with hash
     * 
     * @Param hash: String
     * @Returm URL: String
     */
    def urlFromHash(hash: String): String = {
        val url = hashUrlMap.getOrElse(hash,-1)
        if(url == -1){
        	throw invalidHashException(s"Sorry, that is not a valid hash.")
        }
        addClick(hash)
        url.toString
    }
    
    /**
     * Attaches prefix if needed so all URLs start with http://www.
     * 
     * @Param a URL
     * @Return URL: String
     */
    def attachPrefix(url: String) = url.slice(0, 4) match {
        case "http" => url
        case "www." => "http://" + url
        case _ => "http://www." + url
    }
    
    /**
     * Determines if a URL is valid or not
     * 
     * @Param URL
     * @Return Boolean
     */
    def validUrl(url:String): Boolean = {
        val re = """\(?\bhttp://[-A-Za-z0-9+&@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&@#/%=~_()|]""".r
        re.pattern.matcher(url).matches // true
    }
    
    /**
     * Synchronized method to create Hash based on incremental id,
     * store id associated with url, start a map entry to count clicks,
     * and store url associated with hash
     * 
     * @Param URL
     * @Return hash: String
     */
     /////////////////////////////////////////// Old method
     /*
    def create(url: String): String = synchronized{
    	val hash = convertTo62(id)
       	urlIdMap += (url -> id)  
        hashClicksMap += (hash -> 0)
        hashUrlMap += (hash -> url)
        id += 1	
        hash
    }
    */
    /** 
     * A race condition exists when two+ threads attempt
     * to access a shared variable at the same time
     * this could exist here if many threads attempt
     * to create a new table entry at the same time
     * attempt to solve with actors
    */
    def create(url:String): String = {
        val actorProp = system.actorOf(Props(new urlActor(url)), name = "urlActorProp")
        actorProp ! Create
        actorProp ! PoisonPill
        
        var msg = "msg"
        implicit val timeout = Timeout(5 seconds)
        val future = actorProp ? msg // enabled by the “ask” import
        val result = Await.result(future, timeout.duration).asInstanceOf[String]
        
        // System hangs and does not reach here
        println("result: " + result)
        
        
        // Debug value to satisfy method return
        val str = "str"
        str
    }
    
    class urlActor(url: String) extends Actor {
        def createEntry(url: String): String = {
            val hash = convertTo62(id)
            urlIdMap += (url -> id)  
            hashClicksMap += (hash -> 0)
            hashUrlMap += (hash -> url)
            id += 1
            hash
        }
        def receive = {
            case Create => sender ! createEntry(url)
            case PoisonPill => context.stop(self)
        }
        
    }
	
    
    /**
     * increment click count for hash 
     * 
     * @Param hash: String
     * @Return Unit
     */
    def addClick(hash: String): Unit = synchronized{
        hashClicksMap(hash) = hashClicksMap(hash)+1
    }
	
    /**
     * Retrieve basic click statistics
     * 
     * @Param hash
     * @Return Map of click statd, Map["Description", Info]
     */
	def statsForHash(hash: String): Map[String, Any] = {
        val clicks = hashClicksMap.getOrElse(hash,-1)
        if(clicks == -1){
            throw invalidHashException(s"Sorry, that is not a valid hash.")
        }
        Map("Number of times this url has been clicked: " -> clicks)
	}

	
    /**
     * Convert number to base 62 
     * 
     * @Param number
     * @Return base 62 hash
     */
    def convertTo62(number: Double): String = convertTo62Helper(number, 0, "")
    def convertTo62Helper(number:Double, position:Double, result:String): String = {
       if(number < pow(62,position+1)){
            base62Alphabet((number / pow(62,position)).toInt)+result       
       }else{
            convertTo62Helper(number-remainder(number,(position+1)), 
                position+1, 
                base62Alphabet((remainder(number,(position+1))/pow(62,position)).toInt)+result)
       } 
    }
    
   
   /**
    * Calculate remainder for num1 divided by 62^num2 
    * 
    * @Param num1:Double and num2:Double to be evaluated
    * @Return result: Double
    */
   def remainder(num1:Double, num2:Double): Double = num1 % pow(62,num2)
    
}
  
object actorMain{
    def main(args: Array[String]){
        val act = new urlActorsAttempt
        println("main1: " + act.hashUrl("http://www.a.ca"))
     
    }
}
