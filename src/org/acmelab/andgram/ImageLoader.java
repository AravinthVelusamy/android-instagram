package org.acmelab.andgram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.util.HashMap;
import java.util.Stack;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.ImageView;

public class ImageLoader {

    // the simplest in-memory cache implementation.
    // This should be replaced with something like SoftReference or
    // BitmapOptions.inPurgeable(since 1.6)
    private HashMap<String, SoftReference<Bitmap>> cache=new HashMap<String, SoftReference<Bitmap>>();

    final int stub_id = R.drawable.stub;
    private File cacheDir;

    public ImageLoader(Context context){
        //Make the background thead low priority. This way it will not affect the UI performance
        photoLoaderThread.setPriority(Thread.NORM_PRIORITY-1);

        //Find the dir to save cached images
        if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED))
            cacheDir=new File(android.os.Environment.getExternalStorageDirectory(),Utils.OUTPUT_DIR);
        else
            cacheDir=context.getCacheDir();
        if(!cacheDir.exists())
            cacheDir.mkdirs();
    }

    public void DisplayImage(String url, Activity activity, ImageView imageView)
    {
        Log.i(Utils.TAG, "Display: " + url);
        if(cache.containsKey(url)) {
            SoftReference<Bitmap> softRef = cache.get(url);
            Bitmap bitmap = softRef.get();
            if( bitmap == null ) {
                Log.i(Utils.TAG, "Re-queuing GC'ed image: " + url);
                // maybe? : cache.remove(softRef);
                queuePhoto(url, activity, imageView);
                imageView.setImageResource(stub_id);
            } else {
                imageView.setImageBitmap(softRef.get());
                if( softRef.get() == null ) {
                    Log.e(Utils.TAG, "Null bitmap: " + url);
                }
            }
        }
        else
        {
            queuePhoto(url, activity, imageView);
            imageView.setImageResource(stub_id);
        }
    }

    private void queuePhoto(String url, Activity activity, ImageView imageView)
    {
        Log.i(Utils.TAG, "Queueing: " + url);

        //This ImageView may be used for other images before. So there may be some old tasks in the queue. We need to discard them.
        photosQueue.Clean(imageView);
        PhotoToLoad p=new PhotoToLoad(url, imageView);
        synchronized(photosQueue.photosToLoad){
            photosQueue.photosToLoad.push(p);
            photosQueue.photosToLoad.notifyAll();
        }

        //start thread if it's not started yet
        if(photoLoaderThread.getState()==Thread.State.NEW)
            photoLoaderThread.start();
    }

    private Bitmap getBitmap(String url)
    {
        // I identify images by hashcode. Not a perfect solution, good for the demo.
        String filename=String.valueOf(url.hashCode());
        File f=new File(cacheDir, filename);

        //from SD cache
        Bitmap b = decodeFile(f);
        if(b!=null)
            return b;

        //from web
        try {
            Bitmap bitmap=null;
            InputStream is=new URL(url).openStream();
            OutputStream os = new FileOutputStream(f);
            Utils.CopyStream(is, os);
            os.close();
            bitmap = decodeFile(f);
            return bitmap;
        } catch (Exception ex){
           ex.printStackTrace();
           return null;
        }
    }

    //decodes image and scales it to reduce memory consumption
    private Bitmap decodeFile(File f){
        try {
            //decode image size
            // BitmapFactory.Options o = new BitmapFactory.Options();
            // o.inJustDecodeBounds = true;
            return(BitmapFactory.decodeStream(new FileInputStream(f),null,null));
            /*
            //Find the correct scale value. It should be the power of 2.
            final int REQUIRED_SIZE=70;
            int width_tmp=o.outWidth, height_tmp=o.outHeight;
            int scale=1;
            while(true){
                if(width_tmp/2<REQUIRED_SIZE || height_tmp/2<REQUIRED_SIZE)
                    break;
                width_tmp/=2;
                height_tmp/=2;
                scale*=2;
            }

            //decode with inSampleSize
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize=scale;
            return BitmapFactory.decodeStream(new FileInputStream(f), null, o2);
            */
        } catch (FileNotFoundException e) {}
        return null;
    }

    // Task for the queue
    private class PhotoToLoad
    {
        public String url;
        public ImageView imageView;
        public PhotoToLoad(String u, ImageView i){
            url=u;
            imageView=i;
        }
    }

    PhotosQueue photosQueue=new PhotosQueue();

    public void stopThread()
    {
        photoLoaderThread.interrupt();
    }

    // stores list of photos to download
    class PhotosQueue
    {
        private Stack<PhotoToLoad> photosToLoad=new Stack<PhotoToLoad>();

        //removes all instances of this ImageView
        public void Clean(ImageView image)
        {
            for(int j=0 ;j<photosToLoad.size();){
                if(photosToLoad.get(j).imageView==image)
                    photosToLoad.remove(j);
                else
                    ++j;
            }
        }
    }

    class PhotosLoader extends Thread {
        public void run() {
            try {
                while(true)
                {
                    //thread waits until there are any images to load in the queue
                    if(photosQueue.photosToLoad.size()==0)
                        synchronized(photosQueue.photosToLoad){
                            photosQueue.photosToLoad.wait();
                        }
                    if(photosQueue.photosToLoad.size()!=0)
                    {
                        PhotoToLoad photoToLoad;
                        synchronized(photosQueue.photosToLoad){
                            photoToLoad=photosQueue.photosToLoad.pop();
                        }
                        Bitmap bmp=getBitmap(photoToLoad.url);
                        cache.put(photoToLoad.url, new SoftReference<Bitmap>(bmp));
                        Object tag=photoToLoad.imageView.getTag();
                        if(tag!=null && ((String)tag).equals(photoToLoad.url)){
                            BitmapDisplayer bd=new BitmapDisplayer(bmp, photoToLoad.imageView);
                            Activity a=(Activity)photoToLoad.imageView.getContext();
                            a.runOnUiThread(bd);
                        }
                    }
                    if(Thread.interrupted())
                        break;
                }
            } catch (InterruptedException e) {
                //allow thread to exit
            }
        }
    }

    PhotosLoader photoLoaderThread = new PhotosLoader();

    // Used to display bitmap in the UI thread
    class BitmapDisplayer implements Runnable
    {
        Bitmap bitmap;
        ImageView imageView;
        public BitmapDisplayer(Bitmap b, ImageView i){
            bitmap=b;
            imageView=i;
        }

        public void run()
        {
            if(bitmap!=null)
                imageView.setImageBitmap(bitmap);
            else
                imageView.setImageResource(stub_id);
        }
    }

    public void clearCache() {
        // clear memory cache
        cache.clear();

        //clear SD cache
        File[] files=cacheDir.listFiles();
        for(File f:files)
            f.delete();
    }

}