package yagen.waitmydawn.kb.ui;

import javafx.scene.*;
import javafx.scene.input.*;
import javafx.scene.text.*;
import java.util.*;

/** TextFlow选区覆盖段落和表格；链接保持独立点击，拖选时不触发导航。 */
final class TextSelection {
    private TextSelection() {}
    static void install(Parent root) {
        var flows=new ArrayList<TextFlow>(); collect(root,flows);
        if(flows.isEmpty())return;
        root.setFocusTraversable(true);
        int[] anchor={-1,0},end={-1,0};
        Runnable paint=()->{
            int from=anchor[0],to=end[0],a=anchor[1],b=end[1];
            if(from>to||from==to&&a>b){int n=from;from=to;to=n;n=a;a=b;b=n;}
            for(int i=0;i<flows.size();i++){int offset=0;for(Node child:flows.get(i).getChildren()){
                if(child instanceof Text text){int length=text.getText().length();text.setSelectionStart(i>=from&&i<=to?Math.max(0,Math.min(length,(i==from?a:0)-offset)):0);text.setSelectionEnd(i>=from&&i<=to?Math.max(0,Math.min(length,(i==to?b:Integer.MAX_VALUE)-offset)):0);offset+=length;}
                else offset++;
            }}
        };
        for(int i=0;i<flows.size();i++){
            var flow=flows.get(i);int index=i;
            flow.addEventFilter(MouseEvent.MOUSE_PRESSED,event->{if(event.getButton()!=MouseButton.PRIMARY)return;root.requestFocus();anchor[0]=end[0]=index;anchor[1]=end[1]=flow.hitTest(flow.sceneToLocal(event.getSceneX(),event.getSceneY())).getInsertionIndex();paint.run();});
        }
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED,event->{
            if(anchor[0]<0||!event.isPrimaryButtonDown())return;
            TextFlow nearest=null;double distance=Double.MAX_VALUE;
            for(var flow:flows){var point=flow.sceneToLocal(event.getSceneX(),event.getSceneY());double dy=point.getY()<0?-point.getY():Math.max(0,point.getY()-flow.getHeight());double dx=point.getX()<0?-point.getX():Math.max(0,point.getX()-flow.getWidth());double d=dy*dy+dx*dx;if(d<distance){nearest=flow;distance=d;}}
            if(nearest!=null){end[0]=flows.indexOf(nearest);end[1]=nearest.hitTest(nearest.sceneToLocal(event.getSceneX(),event.getSceneY())).getInsertionIndex();paint.run();event.consume();}
        });
        root.addEventFilter(MouseEvent.MOUSE_CLICKED,event->{if(anchor[0]!=end[0]||anchor[1]!=end[1])event.consume();});
        root.addEventFilter(KeyEvent.KEY_PRESSED,event->{
            if(!event.isShortcutDown()||event.getCode()!=KeyCode.C||anchor[0]<0)return;
            int from=anchor[0],to=end[0],a=anchor[1],b=end[1];if(from>to||from==to&&a>b){int n=from;from=to;to=n;n=a;a=b;b=n;}
            var selected=new StringBuilder();
            for(int i=from;i<=to;i++){
                int offset=0,start=i==from?a:0,stop=i==to?b:Integer.MAX_VALUE;
                for(var child:flows.get(i).getChildren()){
                    int length=child instanceof Text text?text.getText().length():1;
                    int left=Math.max(0,start-offset),right=Math.min(length,stop-offset);
                    if(right>left){if(child instanceof Text text)selected.append(text.getText(),left,right);else if(child instanceof javafx.scene.control.Labeled label)selected.append(label.getText());}
                    offset+=length;
                }
                if(i<to)selected.append('\n');
            }
            if(!selected.isEmpty()){var content=new ClipboardContent();content.putString(selected.toString());Clipboard.getSystemClipboard().setContent(content);event.consume();}
        });
    }
    private static void collect(Node node,List<TextFlow> flows){if(node instanceof TextFlow flow)flows.add(flow);else if(node instanceof Parent parent)for(var child:parent.getChildrenUnmodifiable())collect(child,flows);}
}
