package com.example.camerademo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.RequiresApi
import android.support.v4.app.ActivityCompat
import android.util.Log
import android.util.Size
import android.util.SparseArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date

class MainActivity : Activity() {
    private val TAG = "MainActivity"

    var mPreViewView: TextureView? = null
    var mHandlerThread: HandlerThread? = null
    var mCameraHandler: Handler? = null
    private var mCameraManager: CameraManager? = null
    var mPreviewSize: Size? = null
    var mCaptureSize: Size? = null
    private var mCameraId: String? = null
    var mCameraDevice: CameraDevice? = null
    var mCameraRequestBuilder: CaptureRequest.Builder? = null
    var mCameraRequest: CaptureRequest? = null
    var mCameraCaptureSession: CameraCaptureSession? = null
    var mImageReader: ImageReader? = null
    var mSparseArray = SparseArray<Int>()

    init {
        mSparseArray.append(Surface.ROTATION_0, 90)
        mSparseArray.append(Surface.ROTATION_90, 0)
        mSparseArray.append(Surface.ROTATION_180, 270)
        mSparseArray.append(Surface.ROTATION_270, 180)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main)

        mPreViewView = findViewById(R.id.textureView)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onResume() {
        super.onResume()

        startCameraThread()

        if(!mPreViewView!!.isAvailable){
            mPreViewView!!.surfaceTextureListener= mTextureListener
        }else{
            startPreview()
        }
    }

    fun transformImage(width:Int, height:Int){

        if (mPreViewView == null) {
            return
        } else {
            try{
                var matrix:Matrix = Matrix()
                var rotation:Int = windowManager.defaultDisplay.rotation
                var textureRectF:RectF = RectF(0F, 0F, width.toFloat(), height.toFloat())
                var previewRectF:RectF = RectF(0F, 0F, mPreViewView!!.height.toFloat(),
                    mPreViewView!!.width.toFloat()
                )
                var centerX:Float = textureRectF.centerX()
                var centerY:Float = textureRectF.centerY()

                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    previewRectF.offset(centerX - previewRectF.centerX(), centerY - previewRectF.centerY())
                    matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL)
                    var scale:Float =
                        (width / width.toFloat()).coerceAtLeast(height / width.toFloat())
                    matrix.postScale(scale, scale, centerX, centerY)
                    matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }

                mPreViewView!!.setTransform(matrix)
            } catch (e:Exception) {
                e.printStackTrace()
            }
        }
    }

    private var mTextureListener:TextureView.SurfaceTextureListener = object:TextureView.SurfaceTextureListener{
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            Log.i(TAG, "-->onSurfaceTextureAvailable")
            // 设置摄像头参数
            setUpCamera(width, height)

            // 平板横屏预览显示会旋转90度，此处做方向矫正
            mPreViewView?.let { transformImage(it.width, it.height) }

            // 打开摄像头
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
            Log.i(TAG, "-->onSurfaceTextureSizeChanged")
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            Log.i(TAG, "-->onSurfaceTextureDestroyed")
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
            Log.i(TAG, "-->onSurfaceTextureUpdated")
        }

    }

    /**
     * 打开摄像头
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun openCamera() {
        var permissions:Array<String> = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        var num = 0
        for(permisson:String in permissions){
            if (ActivityCompat.checkSelfPermission(
                    this,
                    permisson
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(permissions, num++)
                return
            }
        }

        mCameraManager?.openCamera(mCameraId, mStateCallback, mCameraHandler)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    var mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "-->onOpened")
            mCameraDevice = camera
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.i(TAG, "-->onDisconnected")
            mCameraDevice?.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.i(TAG, "-->onError")
            mCameraDevice?.close()
            mCameraDevice = null
        }
    }

    /**
     * 设置摄像头参数
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setUpCamera(width: Int, height: Int) {
        mCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        for (cameraId:String in mCameraManager!!.cameraIdList){
            var cameraCharacteristics =  mCameraManager!!.getCameraCharacteristics(cameraId)

            var facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
            if(null != facing && facing == CameraCharacteristics.LENS_FACING_FRONT){
                continue
            }

            var map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if(null != map){
                mPreviewSize = getOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height)

                for (size:Size in map.getOutputSizes(ImageFormat.JPEG)){
                    Log.i(TAG, "setUpCamera-->size:$size")
                }

                mCaptureSize = Collections.max(map.getOutputSizes(ImageFormat.JPEG).asList()
                ) { o1, o2 -> o1!!.width * o1.height - o2!!.width * o2.height }
            }
            // 建立ImageReader，准备存储照片
            setUpImageReader()

            mCameraId = cameraId

            Log.i(TAG, "-->width:$width height:$height-->setUpCamera-->mPreviewSize:$mPreviewSize-->mCaptureSize:$mCaptureSize")

            break
        }
    }

    /**
     * 建立ImageReader，准备存储照片
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setUpImageReader() {
        mImageReader = ImageReader.newInstance(mCaptureSize!!.width, mCaptureSize!!.height, ImageFormat.JPEG, 2)
        mImageReader!!.setOnImageAvailableListener({
            Log.i(TAG, "-->setOnImageAvailableListener")
            mCameraHandler?.post(ImageSaver(it.acquireNextImage()))
            }, mCameraHandler)
    }

    class ImageSaver(image:Image):Runnable{
        var imageNext:Image? = null

        init {
            imageNext = image
        }

        override fun run() {
            var buffer = imageNext!!.planes[0].buffer
            var data = ByteArray(buffer.capacity())
            buffer.get(data)

            var path = Environment.getExternalStorageDirectory().absolutePath + "/DCIM/CameraV2/"
            var imageFile:File = File(path)
            if(!imageFile.exists()){
                imageFile.mkdir()
            }

            var timeStamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            var fileName = path + "IMG_" + timeStamp + ".jpg"

            var fos:FileOutputStream = FileOutputStream(fileName)
            fos.write(data, 0, data.size)

            fos.close()
        }
    }

    /**
     * 获取摄像头能够输出的，最符合我们当前显示界面分辨率的最小值
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getOptimalSize(outputSizes: Array<Size>, width: Int, height: Int):Size {
        var sizeList = ArrayList<Size>()

        for (size:Size in outputSizes){

            Log.i(TAG,"-->outputSizes:$size")
            if(width > height){// 横屏
                if(size.width > width && size.height > height){
                    sizeList.add(size)
                }
            }else{// 竖屏
                if(size.width > height && size.height > width){
                    sizeList.add(size)
                }
            }
        }

        if(sizeList.size > 1){
            return Collections.min(sizeList
            ) { o1, o2 -> o1!!.width * o1.height - o2!!.width * o2.height }
        }

        return sizeList[0]

    }

    /**
     * 开启摄像头线程
     */
    private fun startCameraThread(){
        mHandlerThread = HandlerThread("CameraThread")
        mHandlerThread!!.start()
        mCameraHandler = Handler(mHandlerThread!!.looper)
    }

    /**
     * 开始预览
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startPreview(){

        // 建立图像缓冲区
        var surfaceTexure = mPreViewView?.surfaceTexture
        mPreViewView?.let { surfaceTexure?.setDefaultBufferSize(it.width, it.height) }

        // 得到界面显示对象
        var surface = Surface(surfaceTexure)

        mCameraRequestBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        mCameraRequestBuilder?.addTarget(surface)

        // 建立通道（CaptureRequest和CaptureSession 会话）
        mCameraDevice?.createCaptureSession(arrayOf(surface, mImageReader?.surface).toMutableList(), object :
            CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                mCameraRequest = mCameraRequestBuilder?.build()
                mCameraCaptureSession = session
                mCameraCaptureSession?.setRepeatingRequest(mCameraRequest, null, mCameraHandler)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {

            }

        }, mCameraHandler)

    }

    // 开始拍照
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun capture(view:View){
        // 获取摄像头的请求
        var cameraBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        cameraBuilder?.addTarget(mImageReader?.surface)

        // 获取摄像头方向
        var rotation = windowManager.defaultDisplay.rotation
        // 设置拍照方向
        cameraBuilder?.set(CaptureRequest.JPEG_ORIENTATION, mSparseArray.get(rotation))

        // 停止预览请求
        mCameraCaptureSession?.stopRepeating()

        var callBack = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                Toast.makeText(applicationContext, "拍照结束，相片已保存", Toast.LENGTH_SHORT).show()
                unlock()
                super.onCaptureCompleted(session, request, result)
            }

        }
        mCameraCaptureSession?.capture(cameraBuilder?.build(), callBack, mCameraHandler)
        // 获取图像的缓冲区
        //读取文件的存储权限及操作
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun unlock() {
        mCameraRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        mCameraCaptureSession?.setRepeatingRequest(mCameraRequestBuilder?.build(), null, mCameraHandler)
    }
}
