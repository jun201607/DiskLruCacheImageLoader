package com.cxmscb.cxm.cacheproject;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.os.StatFs;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ListView;



import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import libcore.io.DiskLruCache;

/**
 * 利用DiskLruCache来缓存图片
 */
public class DiskCacheImageLoader {


    private Context mContext;

    private Set<DiskCacheAsyncTask> mTaskSet;

    //DiskLruCache
    private DiskLruCache mDiskCache;


    private static DiskCacheImageLoader mDiskCacheImageLoader;

    public static DiskCacheImageLoader getInstance(Context context){
        if(mDiskCacheImageLoader==null){
            synchronized (DiskCacheImageLoader.class){
                if(mDiskCacheImageLoader==null){
                    mDiskCacheImageLoader = new DiskCacheImageLoader(context);
                }
            }
        }
        return  mDiskCacheImageLoader;
    }


    private DiskCacheImageLoader(Context context) {

        mTaskSet = new HashSet<>();
        mContext = context.getApplicationContext();
        //得到缓存文件
        File diskCacheDir = getDiskCacheDir(mContext, "Bitmap");
        //如果文件不存在 直接创建
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }

        try {
            mDiskCache = DiskLruCache.open(diskCacheDir, 1, 1,
                            1024*1024*20);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    /**
     * 将一个URL转换成bitmap对象
     *
     */
    public Bitmap getBitmapFromURL(String urlStr) {
        Bitmap bitmap;
        InputStream is = null;

        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            is = new BufferedInputStream(connection.getInputStream(), 1024*8);
            bitmap = BitmapFactory.decodeStream(is);
            connection.disconnect();
            return bitmap;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * 将URL中的图片保存到输出流中
     *
     */
    private boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), 1024*8);
            out = new BufferedOutputStream(outputStream, 1024*8);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }



    /**
     * 普通地加载url
     *
     */
    public void loadImage(ImageView imageView,String url){
        //从缓存中取出图片
        Bitmap bitmap = null;
        try {
            bitmap = getBitmapFromDiskCache(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //如果缓存中没有，则需要从网络中下载
        if (bitmap == null) {
            DiskCacheAsyncTask task = new DiskCacheAsyncTask(imageView);
            task.execute(url);
            mTaskSet.add(task);
        } else {
            //如果缓存中有 直接设置
            imageView.setImageBitmap(bitmap);
        }
    }

    /**
     * 为listview加载从start到end的所有的Image
     *
     */
    public void loadTagedImagesInListView(int start, int end,String[] urls,ListView mListView) {
        for (int i = start; i < end; i++) {
            String url = urls[i];
            ImageView imageView = (ImageView) mListView.findViewWithTag(url);
            loadImage(imageView,url);
        }
        Log.i("num of task"," "+mTaskSet.size());
    }



    /**
     * 创建缓存文件
     *
     */
    public File getDiskCacheDir(Context context, String filePath) {
        boolean externalStorageAvailable = Environment
                .getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }

        return new File(cachePath + File.separator + filePath);
    }



    /**
     * 将URL转换成key
     *
     */
    private String hashKeyFormUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将Url的字节数组转换成哈希字符串
     *
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 将Bitmap写入缓存
     *
     */
    private Bitmap addBitmapToDiskCache(String url) throws IOException {


        if (mDiskCache == null) {
            return null;
        }

        //设置key，并根据URL保存输出流的返回值决定是否提交至缓存
        String key = hashKeyFormUrl(url);
        //得到Editor对象
        DiskLruCache.Editor editor = mDiskCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(0);
            if (downloadUrlToStream(url, outputStream)) {
                //提交写入操作
                editor.commit();
            } else {
                //撤销写入操作
                editor.abort();
            }
            mDiskCache.flush();
        }
        return getBitmapFromDiskCache(url);
    }


    /**
     * 从缓存中取出Bitmap
     *
     */
    private Bitmap getBitmapFromDiskCache(String url) throws IOException {

        //如果缓存中为空  直接返回为空
        if (mDiskCache == null) {
            return null;
        }

        //通过key值在缓存中找到对应的Bitmap
        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        //通过key得到Snapshot对象
        DiskLruCache.Snapshot snapShot = mDiskCache.get(key);
        if (snapShot != null) {
            //得到文件输入流
            InputStream ins = snapShot.getInputStream(0);

            bitmap = BitmapFactory.decodeStream(ins);
        }
        return bitmap;
    }






    /**
     * 异步任务类
     */
    private class DiskCacheAsyncTask extends AsyncTask<String, Void, Bitmap> {
        private ImageView imageView;

        public DiskCacheAsyncTask(ImageView imageView){
            this.imageView = imageView;
        }


        @Override
        protected Bitmap doInBackground(String... params) {

            Bitmap bitmap = getBitmapFromURL(params[0]);
            //保存到缓存中
            if (bitmap != null) {
                try {
                    //写入缓存
                    addBitmapToDiskCache(params[0]);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return bitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
            mTaskSet.remove(this);
            Log.i("num of task"," "+mTaskSet.size());
        }
    }

    /**
     * 停止所有当前正在运行的任务
     */
    public void cancelAllTask() {
        if (mTaskSet != null) {
            for (DiskCacheAsyncTask task : mTaskSet) {
                task.cancel(false);
            }
            mTaskSet.clear();
            Log.i("num of task"," "+mTaskSet.size());
        }
    }


}



