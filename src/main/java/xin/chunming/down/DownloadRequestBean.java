package xin.chunming.down;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
 @Data
public class DownloadRequestBean {
   private String url;
   private ArrayList<HashMap<String,String>> header;
}
