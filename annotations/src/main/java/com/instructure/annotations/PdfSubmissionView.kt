/*
 * Copyright (C) 2018 - present Instructure, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package com.instructure.annotations

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.support.annotation.ColorRes
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.instructure.annotations.AnnotationDialogs.AnnotationCommentDialog
import com.instructure.annotations.AnnotationDialogs.AnnotationErrorDialog
import com.instructure.annotations.AnnotationDialogs.FreeTextDialog
import com.instructure.annotations.FileCaching.DocumentListenerSimpleDelegate
import com.instructure.canvasapi2.managers.CanvaDocsManager
import com.instructure.canvasapi2.models.AnnotationMetadata
import com.instructure.canvasapi2.models.ApiValues
import com.instructure.canvasapi2.models.CanvaDocs.CanvaDocAnnotation
import com.instructure.canvasapi2.models.CanvaDocs.CanvaDocAnnotationResponse
import com.instructure.canvasapi2.models.DocSession
import com.instructure.canvasapi2.utils.*
import com.instructure.canvasapi2.utils.weave.*
import com.instructure.pandautils.utils.onClick
import com.instructure.pandautils.utils.setGone
import com.instructure.pandautils.utils.setVisible
import com.instructure.pandautils.utils.toast
import com.instructure.pandautils.views.ProgressiveCanvasLoadingView
import com.pspdfkit.annotations.Annotation
import com.pspdfkit.annotations.AnnotationFlags
import com.pspdfkit.annotations.AnnotationProvider
import com.pspdfkit.annotations.AnnotationType
import com.pspdfkit.annotations.appearance.AssetAppearanceStreamGenerator
import com.pspdfkit.annotations.defaults.*
import com.pspdfkit.annotations.stamps.CustomStampAppearanceStreamGenerator
import com.pspdfkit.annotations.stamps.StampPickerItem
import com.pspdfkit.configuration.PdfConfiguration
import com.pspdfkit.configuration.page.PageLayoutMode
import com.pspdfkit.configuration.page.PageScrollDirection
import com.pspdfkit.document.PdfDocument
import com.pspdfkit.events.Commands
import com.pspdfkit.listeners.DocumentListener
import com.pspdfkit.ui.PdfFragment
import com.pspdfkit.ui.inspector.PropertyInspectorCoordinatorLayout
import com.pspdfkit.ui.inspector.annotation.AnnotationCreationInspectorController
import com.pspdfkit.ui.inspector.annotation.AnnotationEditingInspectorController
import com.pspdfkit.ui.inspector.annotation.DefaultAnnotationCreationInspectorController
import com.pspdfkit.ui.inspector.annotation.DefaultAnnotationEditingInspectorController
import com.pspdfkit.ui.special_mode.controller.AnnotationCreationController
import com.pspdfkit.ui.special_mode.controller.AnnotationEditingController
import com.pspdfkit.ui.special_mode.controller.AnnotationSelectionController
import com.pspdfkit.ui.special_mode.controller.AnnotationTool
import com.pspdfkit.ui.special_mode.manager.AnnotationManager
import com.pspdfkit.ui.toolbar.*
import com.pspdfkit.ui.toolbar.grouping.MenuItemGroupingRule
import kotlinx.coroutines.experimental.Job
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.File
import java.util.*

@SuppressLint("ViewConstructor")
abstract class PdfSubmissionView(context: Context) : FrameLayout(context), AnnotationManager.OnAnnotationCreationModeChangeListener, AnnotationManager.OnAnnotationEditingModeChangeListener {

    protected val pdfConfiguration: PdfConfiguration = PdfConfiguration.Builder()
            .scrollDirection(PageScrollDirection.VERTICAL)
            .enabledAnnotationTools(setupAnnotationCreationList())
            .editableAnnotationTypes(setupAnnotationEditList())
            .setAnnotationInspectorEnabled(true)
            .textSharingEnabled(false)
            .layoutMode(PageLayoutMode.SINGLE)
            .textSelectionEnabled(false)
            .disableCopyPaste()
            .build()
    protected val annotationCreationToolbar = AnnotationCreationToolbar(context)
    protected val annotationEditingToolbar = AnnotationEditingToolbar(context)
    protected var annotationEditingInspectorController: AnnotationEditingInspectorController? = null
    protected var annotationCreationInspectorController: AnnotationCreationInspectorController? = null
    protected var pdfFragment: PdfFragment? = null
    protected var fileJob: Job? = null
    protected var createAnnotationJob: Job? = null
    protected var updateAnnotationJob: Job? = null
    protected var deleteAnnotationJob: Job? = null
    protected var pdfContentJob: Job? = null
    protected var annotationsJob: Job? = null
    protected var sendCommentJob: Job? = null
    protected lateinit var docSession: DocSession
    protected val commentRepliesHashMap: HashMap<String, ArrayList<CanvaDocAnnotation>> = HashMap()
    protected var currentAnnotationModeTool: AnnotationTool? = null
    protected var currentAnnotationModeType: AnnotationType? = null

    protected var isUpdatingWithNoNetwork = false
    protected val supportFragmentManager = (context as AppCompatActivity).supportFragmentManager

    @get:ColorRes
    abstract val progressColor: Int
    abstract val annotationToolbarLayout: ToolbarCoordinatorLayout
    abstract val inspectorCoordinatorLayout: PropertyInspectorCoordinatorLayout
    abstract val commentsButton: ImageView
    abstract val loadingContainer: FrameLayout
    abstract val progressBar: ProgressiveCanvasLoadingView

    abstract fun setFragment(fragment: Fragment)
    abstract fun showNoInternetDialog()
    abstract fun disableViewPager()
    abstract fun enableViewPager()
    abstract fun setIsCurrentlyAnnotating(boolean: Boolean)
    abstract fun showAnnotationComments(commentList: ArrayList<CanvaDocAnnotation>, headAnnotationId: String, docSession: DocSession)
    abstract fun showFileError()


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initializeSubmissionView()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterPdfFragmentListeners()
        createAnnotationJob?.cancel()
        updateAnnotationJob?.cancel()
        deleteAnnotationJob?.cancel()
        annotationsJob?.cancel()
        pdfContentJob?.cancel()
        fileJob?.cancel()
    }

    protected fun unregisterPdfFragmentListeners() {
        pdfFragment?.removeOnAnnotationCreationModeChangeListener(this)
        pdfFragment?.removeOnAnnotationEditingModeChangeListener(this)
        pdfFragment?.document?.annotationProvider?.removeOnAnnotationUpdatedListener(mAnnotationUpdateListener)
        pdfFragment?.removeOnAnnotationSelectedListener(annotationSelectedListener)
        pdfFragment?.removeOnAnnotationDeselectedListener(mAnnotationDeselectedListener)
    }

    /**
     * THIS HAS TO BE CALLED
     */
    @Throws(UninitializedPropertyAccessException::class)
    protected fun initializeSubmissionView() {
        annotationEditingInspectorController = DefaultAnnotationEditingInspectorController(context, inspectorCoordinatorLayout)
        annotationCreationInspectorController = DefaultAnnotationCreationInspectorController(context, inspectorCoordinatorLayout)

        annotationToolbarLayout.setOnContextualToolbarLifecycleListener(object : ToolbarCoordinatorLayout.OnContextualToolbarLifecycleListener{
            override fun onDisplayContextualToolbar(p0: ContextualToolbar<*>) {}
            override fun onRemoveContextualToolbar(p0: ContextualToolbar<*>) {}

            override fun onPrepareContextualToolbar(toolbar: ContextualToolbar<*>) {
                toolbar.layoutParams = ToolbarCoordinatorLayout.LayoutParams(
                        ToolbarCoordinatorLayout.LayoutParams.Position.TOP, EnumSet.of(ToolbarCoordinatorLayout.LayoutParams.Position.TOP)
                )
            }
        })

        annotationCreationToolbar.closeButton.setGone()

        annotationCreationToolbar.setMenuItemGroupingRule(object : MenuItemGroupingRule {
            override fun groupMenuItems(items: MutableList<ContextualToolbarMenuItem>, i: Int) = configureCreationMenuItemGrouping(items, i)
            override fun areGeneratedGroupItemsSelectable() = true
        })

        annotationEditingToolbar.setMenuItemGroupingRule(object : MenuItemGroupingRule {
            override fun groupMenuItems(items: MutableList<ContextualToolbarMenuItem>, i: Int) = configureEditMenuItemGrouping(items)
            override fun areGeneratedGroupItemsSelectable() = true
        })

        annotationEditingToolbar.setOnMenuItemClickListener { _, contextualToolbarMenuItem ->
            if (contextualToolbarMenuItem.title == context.getString(com.pspdfkit.R.string.pspdf__edit) &&
                    currentAnnotationModeType == AnnotationType.FREETEXT) {

                val dialog = FreeTextDialog.getInstance(supportFragmentManager, true, pdfFragment?.selectedAnnotations?.get(0)?.contents ?: "", freeTextDialogCallback)
                dialog.show(supportFragmentManager, FreeTextDialog::class.java.simpleName)

                return@setOnMenuItemClickListener true
            }

            if (contextualToolbarMenuItem.title == context.getString(com.pspdfkit.R.string.pspdf__delete)) {
                val annotation = pdfFragment?.selectedAnnotations?.get(0)
                // Remove the annotation
                if(annotation != null) {
                    pdfFragment?.document?.annotationProvider?.removeAnnotationFromPage(annotation)
                    pdfFragment?.notifyAnnotationHasChanged(annotation)
                    pdfFragment?.clearSelectedAnnotations()
                    pdfFragment?.enterAnnotationCreationMode()
                    return@setOnMenuItemClickListener true
                }
            }
            return@setOnMenuItemClickListener false
        }

        configureCommentView(commentsButton)
    }

    private fun configureCommentView(commentsButton: ImageView) {
        //we want to offset the comment button by the height of the action bar
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        val typedArray = context.obtainStyledAttributes(typedValue.resourceId, intArrayOf(android.R.attr.actionBarSize))
        val actionBarDp = typedArray.getDimensionPixelSize(0, -1)
        typedArray.recycle()

        val marginDp = TypedValue.applyDimension( TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics)
        val layoutParams = commentsButton.layoutParams as LayoutParams
        commentsButton.drawable.setTint(Color.WHITE)
        layoutParams.gravity = Gravity.END or Gravity.TOP
        layoutParams.topMargin = marginDp.toInt() + actionBarDp
        layoutParams.rightMargin = marginDp.toInt()

        commentsButton.onClick {
            openComments()
        }
    }

    private fun openComments() {
        // Get current annotation in both forms
        val currentPdfAnnotation = pdfFragment?.selectedAnnotations?.get(0)
        val currentAnnotation = currentPdfAnnotation?.convertPDFAnnotationToCanvaDoc(docSession.documentId)
        // Assuming neither is null, continue
        if(currentPdfAnnotation != null && currentAnnotation != null) {
            // If the contents of the current annotation are empty we want to prompt them to add a comment
            if(commentRepliesHashMap[currentAnnotation.annotationId] == null || commentRepliesHashMap[currentAnnotation.annotationId]?.isEmpty() == true) {
                // No comments for this annotation, show a dialog for the user to add some if they want
                AnnotationCommentDialog.getInstance(supportFragmentManager, "", context.getString(R.string.addAnnotationComment)) { _, text ->
                    setIsCurrentlyAnnotating(true) //don't want the sliding panel getting in the way
                    // Create new comment reply for this annotation.
                    if(text.isValid()) {
                        createCommentAnnotation(currentAnnotation.annotationId, currentAnnotation.page, text)
                    }
                }.show(supportFragmentManager, AnnotationCommentDialog::class.java.simpleName)
            } else {
                // Otherwise, show the comment list fragment
                commentRepliesHashMap[currentAnnotation.annotationId]?.let {
                    if(!it.isEmpty()) {
                        showAnnotationComments(it, currentAnnotation.annotationId, docSession)
                    }
                }
            }
        }
    }

    open fun setupPSPDFKit(uri: Uri) {
        val newPdfFragment = PdfFragment.newInstance(uri, pdfConfiguration)
        setFragment(newPdfFragment)
        pdfFragment = newPdfFragment
        pdfFragment?.addOnAnnotationCreationModeChangeListener(this)
        pdfFragment?.addOnAnnotationEditingModeChangeListener(this)

        if(docSession.annotationMetadata.canWrite()) {
            // push the pdf viewing screen under the toolbar
            pdfFragment?.setInsets(0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, context.resources.displayMetrics).toInt(), 0, 0)
        }

        attachDocListener()
        setupPdfAnnotationDefaults()
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    protected fun handlePdfContent(url: String) {
        pdfContentJob = tryWeave {
            if(url.contains("canvadoc")) {
                val redirectUrl = getCanvaDocsRedirect(url)
                //extract the domain for API use
                if (redirectUrl.isNotEmpty()) {
                    docSession = awaitApi { CanvaDocsManager.getCanvaDoc(redirectUrl, it) }
                    docSession.let {
                        val canvaDocsDomain = extractCanvaDocsDomain(redirectUrl)
                        val pdfUrl = canvaDocsDomain + it.annotationUrls.pdfDownload
                        it.apiValues = ApiValues(it.documentId, pdfUrl, extractSessionId(pdfUrl), canvaDocsDomain)
                    }

                    load(docSession.apiValues.pdfUrl) { setupPSPDFKit(it) }
                } else {
                    //TODO: handle case where redirect url is empty, could be canvadoc failure case
                }
            } else {
                //keep things working if they don't have canvadocs
                load(url) { setupPSPDFKit(it) }
            }
        } catch {
            // Show error
            toast(R.string.errorOccurred)
            it.printStackTrace()
        }
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    open fun attachDocListener() {
        pdfFragment?.addDocumentListener(object : DocumentListener by DocumentListenerSimpleDelegate() {
            override fun onDocumentLoaded(pdfDocument: PdfDocument) {
                loadCustomAppearenceGenerators()

                pdfFragment?.enterAnnotationCreationMode()
                if (!docSession.annotationMetadata.canRead()) return
                annotationsJob = tryWeave {
                    // Snag them annotations with the session id
                    val annotations = awaitApi<CanvaDocAnnotationResponse> { CanvaDocsManager.getAnnotations(docSession.apiValues.sessionId, docSession.apiValues.canvaDocsDomain, it) }
                    // We don't want to trigger the annotation events here, so unregister and re-register after
                    pdfFragment?.document?.annotationProvider?.removeOnAnnotationUpdatedListener(mAnnotationUpdateListener)

                    // Grab all the annotations
                    for (item in annotations.data) {
                        if (item.annotationType == CanvaDocAnnotation.AnnotationType.COMMENT_REPLY) {
                            // Grab the annotation comments and store them to be displayed later when user selects annotation
                            if (commentRepliesHashMap.containsKey(item.inReplyTo)) {
                                commentRepliesHashMap[item.inReplyTo]?.add(item)
                            } else {
                                commentRepliesHashMap[item.inReplyTo!!] = arrayListOf(item)
                            }
                        } else {
                            // We don't want to add deleted annotations to the view
                            if (!item.deleted) {
                                val annotation = item.convertCanvaDocAnnotationToPDF(this@PdfSubmissionView.context)
                                if (annotation != null) {
                                    // If the user doesn't have at least write permissions we need to lock down all annotations
                                    if (!docSession.annotationMetadata.canWrite()) {
                                        annotation.flags = EnumSet.of(AnnotationFlags.LOCKED, AnnotationFlags.LOCKEDCONTENTS, AnnotationFlags.NOZOOM)
                                    } else {
                                        if (item.userId != docSession.annotationMetadata.userId) {
                                            annotation.flags = EnumSet.of(AnnotationFlags.LOCKED, AnnotationFlags.LOCKEDCONTENTS, AnnotationFlags.NOZOOM)
                                        }
                                    }

                                    pdfFragment?.document?.annotationProvider?.addAnnotationToPage(annotation)
                                    pdfFragment?.notifyAnnotationHasChanged(annotation)
                                }
                            }
                        }

                    }
                    pdfFragment?.document?.annotationProvider?.addOnAnnotationUpdatedListener(mAnnotationUpdateListener)
                    pdfFragment?.addOnAnnotationSelectedListener(annotationSelectedListener)
                    pdfFragment?.addOnAnnotationDeselectedListener(mAnnotationDeselectedListener)
                } catch {
                    // Show error
                    toast(R.string.annotationErrorOccurred)
                    it.printStackTrace()
                }
            }
        })
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    protected fun load(url: String?, onFinished: (Uri) -> Unit) {
        fileJob?.cancel()
        fileJob = tryWeave {
            progressBar.isIndeterminate = true
            progressBar.setColor(ContextCompat.getColor(this@PdfSubmissionView.context, R.color.utils_defaultTextGray))
            val teacherYellow = ContextCompat.getColor(this@PdfSubmissionView.context, progressColor)

            val jitterThreshold = 300L
            val showLoadingRunner = Runnable {
                loadingContainer.setVisible()
                progressBar.announceForAccessibility(getContext().getString(R.string.loading))
            }
            val startTime = System.currentTimeMillis()
            val handler = Handler()
            handler.postDelayed(showLoadingRunner, jitterThreshold)

            // If we don't have a url we'll display an error
            val tempFile: File? = com.instructure.annotations.FileCaching.FileCache.awaitFileDownload(url!!) {
                onUI {
                    progressBar.setColor(teacherYellow)
                    progressBar.setProgress(it)
                }
            }

            if (tempFile != null) {
                progressBar.isIndeterminate = true
                onFinished(Uri.fromFile(tempFile))
            } else {
                showFileError()
            }

            val passedTime = System.currentTimeMillis() - startTime
            val hideLoadingRunner = Runnable { loadingContainer.setGone() }
            when {
                passedTime < jitterThreshold -> {
                    handler.removeCallbacks(showLoadingRunner); hideLoadingRunner.run()
                }
                passedTime < jitterThreshold * 2 -> handler.postDelayed(hideLoadingRunner, (jitterThreshold * 2) - passedTime)
                else -> hideLoadingRunner.run()
            }
        } catch {
            // Show error
            toast(R.string.annotationErrorOccurred)
            it.printStackTrace()
        }
    }

    val mAnnotationUpdateListener = object: AnnotationProvider.OnAnnotationUpdatedListener {
        override fun onAnnotationCreated(annotation: Annotation) {
            if(!annotation.isAttached || annotationNetworkCheck(annotation)) return

            // If it's a freetext and it's empty that means that they haven't had a chance to fill it out
            if((annotation.type == AnnotationType.FREETEXT) && annotation.contents.isNullOrEmpty()){
                return
            }

            // If its a stamp we need to modify its rect to match the webs stamp size
            if (annotation.type == AnnotationType.STAMP) annotation.transformStamp()

            createNewAnnotation(annotation)
        }

        override fun onAnnotationUpdated(annotation: Annotation) {
            if(!annotation.isAttached || annotationNetworkCheck(annotation)) return

            if (!annotation.flags.contains(AnnotationFlags.LOCKED) && annotation.isModified && annotation.name.isValid()) {
                //we only want to update the annotation if it isn't locked and IS modified
                updateAnnotation(annotation)
            }
        }

        override fun onAnnotationRemoved(annotation: Annotation) {
            if(annotationNetworkCheck(annotation)) return

            //removed annotation
            if(annotation.name.isValid()) {
                deleteAnnotation(annotation)
            }
        }
    }

    private fun annotationNetworkCheck(annotation: Annotation): Boolean {
        if(!APIHelper.hasNetworkConnection()) {
            if(isUpdatingWithNoNetwork) {
                isUpdatingWithNoNetwork = false
                return true
            } else {
                isUpdatingWithNoNetwork = true
                if(annotation.isAttached) {
                    pdfFragment?.eventBus?.post(Commands.ClearSelectedAnnotations())
                    pdfFragment?.document?.annotationProvider?.removeAnnotationFromPage(annotation)
                    pdfFragment?.notifyAnnotationHasChanged(annotation)
                }
                showNoInternetDialog()
            }
        }
        return false
    }

    val annotationSelectedListener = object: AnnotationManager.OnAnnotationSelectedListener {
        override fun onAnnotationSelected(annotation: Annotation, isCreated: Boolean) {}
        override fun onPrepareAnnotationSelection(p0: AnnotationSelectionController, annotation: Annotation, isCreated: Boolean): Boolean {
            if (APIHelper.hasNetworkConnection()) {
                if (annotation.type == AnnotationType.FREETEXT && annotation.contents.isNullOrEmpty()) {
                    //this is a new free text annotation, and needs to be selected to be created
                    if (supportFragmentManager.findFragmentByTag(FreeTextDialog::class.java.simpleName) == null) {
                        val dialog = FreeTextDialog.getInstance(supportFragmentManager, false,"", freeTextDialogCallback)
                        dialog.show(supportFragmentManager, FreeTextDialog::class.java.simpleName)
                    }
                } else if (annotation.type == AnnotationType.FREETEXT) {
                    setIsCurrentlyAnnotating(true)
                }

                if(annotation.type != AnnotationType.FREETEXT && annotation.name.isValid()) {
                    // if the annotation is an existing annotation (has an ID) and is NOT freetext
                    // we want to display the button to view/make comments
                    commentsButton.setVisible()
                }
            }
            return true
        }
    }

    val mAnnotationDeselectedListener = AnnotationManager.OnAnnotationDeselectedListener { _, _->
        commentsButton.setGone()
    }

    //region Annotation Manipulation
    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    fun createNewAnnotation(annotation: Annotation) {
        // This is a new annotation; Post it
        commentsButton.isEnabled = false

        createAnnotationJob = tryWeave {
            val canvaDocAnnotation = annotation.convertPDFAnnotationToCanvaDoc(docSession.apiValues.documentId)
            if (canvaDocAnnotation != null) {
                val newAnnotation = awaitApi<CanvaDocAnnotation> { CanvaDocsManager.putAnnotation(docSession.apiValues.sessionId, generateAnnotationId(), canvaDocAnnotation, docSession.apiValues.canvaDocsDomain, it) }

                // Edit the annotation with the appropriate id
                annotation.name = newAnnotation.annotationId
                pdfFragment?.document?.annotationProvider?.removeOnAnnotationUpdatedListener(mAnnotationUpdateListener)
                pdfFragment?.notifyAnnotationHasChanged(annotation)
                pdfFragment?.document?.annotationProvider?.addOnAnnotationUpdatedListener(mAnnotationUpdateListener)
                commentsButton.isEnabled = true
                if (annotation.type == AnnotationType.STAMP) {
                    commentsButton.setVisible()
                    openComments()
                }
            }
        } catch {
            // Show general error, make more specific in the future?
            toast(R.string.errorOccurred)
            it.printStackTrace()
            commentsButton.isEnabled = true
        }
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    private fun updateAnnotation(annotation: Annotation) {
        // Annotation modified; Update it
        updateAnnotationJob = tryWeave {
            val canvaDocAnnotation = annotation.convertPDFAnnotationToCanvaDoc(docSession.apiValues.documentId)
            if (canvaDocAnnotation != null && !annotation.name.isNullOrEmpty()) {
                awaitApi<CanvaDocAnnotation> { CanvaDocsManager.putAnnotation(docSession.apiValues.sessionId, annotation.name!!, canvaDocAnnotation, docSession.apiValues.canvaDocsDomain, it) }
            }
        } catch {
            if(it is StatusCallbackError) {
                if (it.response?.raw()?.code() == 404) {
                    // Not found; Annotation has been deleted and no longer exists.
                    val dialog = AnnotationErrorDialog.getInstance(supportFragmentManager, {
                        // Delete annotation after user clicks OK on dialog
                        pdfFragment?.eventBus?.post(Commands.ClearSelectedAnnotations())
                        pdfFragment?.document?.annotationProvider?.removeAnnotationFromPage(annotation)
                        pdfFragment?.notifyAnnotationHasChanged(annotation)
                    })
                    dialog.show(supportFragmentManager, AnnotationErrorDialog::class.java.simpleName)
                }
            }

            // Show general error, make more specific in the future?
            toast(R.string.errorOccurred)

            it.printStackTrace()
        }
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    private fun deleteAnnotation(annotation: Annotation) {
        // Annotation deleted; DELETE
        deleteAnnotationJob = tryWeave {
            // If it is not found, don't hit the server (it will fail)
            if (!annotation.name.isNullOrEmpty())
                awaitApi<ResponseBody> { CanvaDocsManager.deleteAnnotation(docSession.apiValues.sessionId, annotation.name!!, docSession.apiValues.canvaDocsDomain, it) }
        } catch {
            // Show general error, make more specific in the future?
            toast(R.string.errorOccurred)
            it.printStackTrace()
        }
    }

    @Suppress("EXPERIMENTAL_FEATURE_WARNING")
    private fun createCommentAnnotation(inReplyToId: String, page: Int, comment: String?) {
        // Annotation modified; Update it
        commentsButton.isEnabled = false

        sendCommentJob = tryWeave {
            val newCommentReply = awaitApi<CanvaDocAnnotation> {
                CanvaDocsManager.putAnnotation(docSession.apiValues.sessionId, generateAnnotationId(), createCommentReplyAnnotation(comment ?: "", inReplyToId, docSession.apiValues.documentId, ApiPrefs.user?.id.toString(), page), docSession.apiValues.canvaDocsDomain, it)
            }

            // The put request doesn't return this property, so we need to set it to true
            newCommentReply.isEditable = true
            commentRepliesHashMap[inReplyToId] = arrayListOf(newCommentReply)
            commentsButton.isEnabled = true
        } catch {
            // Show general error, make more specific in the future?
            toast(R.string.errorOccurred)
            it.printStackTrace()
            commentsButton.isEnabled = true
        }
    }

    private fun setupAnnotationCreationList(): MutableList<AnnotationTool> {
        return mutableListOf(AnnotationTool.INK, AnnotationTool.HIGHLIGHT, AnnotationTool.STRIKEOUT, AnnotationTool.SQUARE, AnnotationTool.STAMP, AnnotationTool.FREETEXT)
    }

    private fun setupAnnotationEditList(): MutableList<AnnotationType> {
        return mutableListOf(AnnotationType.INK, AnnotationType.HIGHLIGHT, AnnotationType.STRIKEOUT, AnnotationType.SQUARE, AnnotationType.STAMP, AnnotationType.FREETEXT)
    }

    //region annotation listeners
    override fun onEnterAnnotationCreationMode(controller: AnnotationCreationController) {
        // We never want to show annotation toolbars if the user doesn't have permission to write
        if(!docSession.annotationMetadata.canWrite()) return
        //we only want to disable the viewpager if they are actively annotating
        if(controller.activeAnnotationTool != AnnotationTool.NONE) disableViewPager()
        annotationCreationInspectorController?.bindAnnotationCreationController(controller)
        annotationCreationToolbar.bindController(controller)
        annotationToolbarLayout.displayContextualToolbar(annotationCreationToolbar, true)

        currentAnnotationModeTool = controller.activeAnnotationTool
    }

    override fun onExitAnnotationCreationMode(p0: AnnotationCreationController) {
        enableViewPager()
        annotationToolbarLayout.removeContextualToolbar(true)
        annotationCreationToolbar.unbindController()
        annotationCreationInspectorController?.unbindAnnotationCreationController()

        currentAnnotationModeTool = AnnotationTool.NONE
    }

    override fun onEnterAnnotationEditingMode(controller: AnnotationEditingController) {
        // We never want to show annotation toolbars if the user doesn't have permission to write
        if(!docSession.annotationMetadata.canWrite()) return
        currentAnnotationModeType = controller.currentlySelectedAnnotation?.type
        //we only want to disable the viewpager if they are actively annotating
        if(controller.currentlySelectedAnnotation != null) disableViewPager()
        annotationEditingToolbar.bindController(controller)
        annotationEditingInspectorController?.bindAnnotationEditingController(controller)
        annotationToolbarLayout.displayContextualToolbar(annotationEditingToolbar, true)
    }

    override fun onExitAnnotationEditingMode(controller: AnnotationEditingController) {
        enableViewPager()
        annotationToolbarLayout.removeContextualToolbar(true)
        annotationEditingToolbar.unbindController()
        annotationEditingInspectorController?.unbindAnnotationEditingController()

        currentAnnotationModeType = AnnotationType.NONE

        //send them back to creating annotations
        pdfFragment?.enterAnnotationCreationMode()
    }

    override fun onChangeAnnotationEditingMode(controller: AnnotationEditingController) {
        currentAnnotationModeType = controller.currentlySelectedAnnotation?.type

        //we only want to disable the viewpager if they are actively annotating
        if(controller.currentlySelectedAnnotation != null) disableViewPager()
        else enableViewPager()
    }

    override fun onChangeAnnotationCreationMode(controller: AnnotationCreationController) {
        //we only want to disable the viewpager if they are actively annotating
        if(controller.activeAnnotationTool != AnnotationTool.NONE) disableViewPager()
        enableViewPager()

        //we want to make sure that the keyboard doesn't mess up the view if they are using these annotations
        setIsCurrentlyAnnotating(controller.activeAnnotationTool != AnnotationTool.NONE)

        currentAnnotationModeTool = controller.activeAnnotationTool!!
    }

    private fun setupPdfAnnotationDefaults() {
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.INK, object: InkAnnotationDefaultsProvider(context) {
            override fun getAvailableColors(): IntArray = context.resources.getIntArray(R.array.standardAnnotationColors)
            override fun getDefaultColor(): Int = ContextCompat.getColor(context, R.color.blueAnnotation)
            override fun getSupportedProperties(): EnumSet<AnnotationProperty> = EnumSet.of(AnnotationProperty.COLOR)
            override fun getDefaultThickness(): Float = 2f
        })
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.FREETEXT, object: FreeTextAnnotationDefaultsProvider(context) {
            override fun getAvailableColors(): IntArray = context.resources.getIntArray(R.array.standardAnnotationColors)
            override fun getDefaultColor(): Int = ContextCompat.getColor(context, R.color.darkGrayAnnotation)
            override fun getSupportedProperties(): EnumSet<AnnotationProperty> = EnumSet.of(AnnotationProperty.COLOR)
            override fun getDefaultTextSize(): Float = 12f
            override fun getDefaultFillColor(): Int = ContextCompat.getColor(context, R.color.white)
        })
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.SQUARE, object: ShapeAnnotationDefaultsProvider(context, AnnotationType.SQUARE) {
            override fun getAvailableColors(): IntArray = context.resources.getIntArray(R.array.standardAnnotationColors)
            override fun getDefaultColor(): Int = ContextCompat.getColor(context, R.color.blueAnnotation)
            override fun getSupportedProperties(): EnumSet<AnnotationProperty> = EnumSet.of(AnnotationProperty.COLOR)
            override fun getDefaultThickness(): Float = 2f
        })
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.STRIKEOUT, object: MarkupAnnotationDefaultsProvider(context, AnnotationType.STRIKEOUT) {
            override fun getAvailableColors(): IntArray = context.resources.getIntArray(R.array.standardAnnotationColors)
            override fun getDefaultColor(): Int = ContextCompat.getColor(context, R.color.redAnnotation)
        })
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.HIGHLIGHT, object: MarkupAnnotationDefaultsProvider(context, AnnotationType.HIGHLIGHT) {
            override fun getAvailableColors(): IntArray = context.resources.getIntArray(R.array.highlightAnnotationColors)
            override fun getDefaultColor(): Int = ContextCompat.getColor(context, R.color.yellowHighlightAnnotation)
        })
        pdfFragment?.setAnnotationDefaultsProvider(AnnotationType.STAMP, object: StampAnnotationDefaultsProvider(context) {
            override fun getStampsForPicker() = getAppearenceStreams()
            override fun getSupportedProperties() = EnumSet.noneOf(AnnotationProperty::class.java)
        })
    }

    // region Stamp Appearance Streams
    private fun getAppearenceStreams(): MutableList<StampPickerItem> {
        val stamps = ArrayList<StampPickerItem>()

        // Create appearance stream generators with a PDF containing vector logo.
        val black = AssetAppearanceStreamGenerator(blackStampFile)
        val blue = AssetAppearanceStreamGenerator(blueStampFile)
        val brown = AssetAppearanceStreamGenerator(brownStampFile)
        val green = AssetAppearanceStreamGenerator(greenStampFile)
        val navy = AssetAppearanceStreamGenerator(navyStampFile)
        val orange = AssetAppearanceStreamGenerator(orangeStampFile)
        val pink = AssetAppearanceStreamGenerator(pinkStampFile)
        val purple = AssetAppearanceStreamGenerator(purpleStampFile)
        val red = AssetAppearanceStreamGenerator(redStampFile)
        val yellow = AssetAppearanceStreamGenerator(yellowStampFile)

        // Create picker items with custom subject and custom appearance stream generator set.
        stamps.add(StampPickerItem.fromSubject(context, blackStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(black)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, blueStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(blue)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, brownStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(brown)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, greenStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(green)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, navyStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(navy)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, orangeStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(orange)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, pinkStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(pink)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, purpleStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(purple)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, redStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(red)
                .build())

        stamps.add(StampPickerItem.fromSubject(context, yellowStampSubject)
                .withSize(18.66f, 26.66f)
                .withAppearanceStreamGenerator(yellow)
                .build())

        return stamps
    }

    private fun loadCustomAppearenceGenerators() {
        // Register custom stamp appearance stream generator as a global appearance stream generator.
        val customStampAppearanceStreamGenerator = CustomStampAppearanceStreamGenerator()
        pdfFragment?.document?.annotationProvider?.addAppearanceStreamGenerator(customStampAppearanceStreamGenerator)

        // Create appearance stream generators with a PDF containing vector logo.
        val black = AssetAppearanceStreamGenerator(blackStampFile)
        val blue = AssetAppearanceStreamGenerator(blueStampFile)
        val brown = AssetAppearanceStreamGenerator(brownStampFile)
        val green = AssetAppearanceStreamGenerator(greenStampFile)
        val navy = AssetAppearanceStreamGenerator(navyStampFile)
        val orange = AssetAppearanceStreamGenerator(orangeStampFile)
        val pink = AssetAppearanceStreamGenerator(pinkStampFile)
        val purple = AssetAppearanceStreamGenerator(purpleStampFile)
        val red = AssetAppearanceStreamGenerator(redStampFile)
        val yellow = AssetAppearanceStreamGenerator(yellowStampFile)

        // Register created appearance stream generator for the custom subject.
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(blackStampSubject, black)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(blueStampSubject, blue)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(brownStampSubject, brown)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(greenStampSubject, green)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(navyStampSubject, navy)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(orangeStampSubject, orange)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(pinkStampSubject, pink)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(purpleStampSubject, purple)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(redStampSubject, red)
        customStampAppearanceStreamGenerator.addAppearanceStreamGenerator(yellowStampSubject, yellow)
    }
    //endregion

    open fun configureCreationMenuItemGrouping(toolbarMenuItems: MutableList<ContextualToolbarMenuItem>, capacity: Int): MutableList<ContextualToolbarMenuItem> {
        //There are 7 items total, and always need to leave room for the color, it has to show.
        //First we need to get all of the items and store them in variables for readability.... rip
        var freeText: ContextualToolbarMenuItem? = null
        var stamp: ContextualToolbarMenuItem? = null
        var strikeOut: ContextualToolbarMenuItem? = null
        var highlight: ContextualToolbarMenuItem? = null
        var ink: ContextualToolbarMenuItem? = null
        var rectangle: ContextualToolbarMenuItem? = null
        var color: ContextualToolbarMenuItem? = null
        var undo: ContextualToolbarMenuItem? = null
        var redo: ContextualToolbarMenuItem? = null

        for (item in toolbarMenuItems) {
            when (item.title) {
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_freetext) -> {
                    freeText = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_stamp) -> {
                    stamp = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_strikeout) -> {
                    strikeOut = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_highlight) -> {
                    highlight = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_ink) -> {
                    ink = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__annotation_type_square) -> {
                    rectangle = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__edit_menu_color) -> {
                    color = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__undo) -> {
                    // There are two menu items called undo, we want the first one.
                    if (undo == null) undo = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__redo) -> {
                    redo = item
                }
            }
        }

        //check to make sure we have all of our items
        if (freeText != null && stamp != null && strikeOut != null && highlight != null
                && ink != null && rectangle != null && color != null && undo != null && redo != null) {
            when {
                capacity >= 8 -> {
                    val inkGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), ink.position, true, mutableListOf(ink, rectangle), ink)
                    return mutableListOf(stamp, highlight, freeText, strikeOut, inkGroup, color, undo, redo)
                }
                capacity == 7 || capacity == 6  -> {
                    val inkGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), ink.position, true, mutableListOf(ink, rectangle), ink)
                    val highlightGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), highlight.position, true, mutableListOf(highlight, strikeOut), highlight)
                    return mutableListOf(stamp, freeText, highlightGroup, inkGroup, color, undo, redo)
                }
                capacity == 5 -> {
                    val inkGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), ink.position, true, mutableListOf(ink, rectangle), ink)
                    val textGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), freeText.position, true, mutableListOf(freeText, stamp, highlight, strikeOut), freeText)
                    return mutableListOf(textGroup, inkGroup, color, undo, redo)
                }
                capacity == 4 -> {
                    val inkGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), ink.position, true, mutableListOf(ink, rectangle), ink)
                    val textGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), freeText.position, true, mutableListOf(freeText, stamp, highlight, strikeOut), freeText)
                    val undoGroup = ContextualToolbarMenuItem.createGroupItem(View.generateViewId(), undo.position, true, mutableListOf(undo, redo), undo)
                    return mutableListOf(textGroup, inkGroup, color, undoGroup)
                }
                //if all else fails, return default grouping unchanged
                else -> {
                    return toolbarMenuItems
                }
            }
        } else {
            //if we dont have all items, just return the default that we have
            return toolbarMenuItems
        }
    }


    open fun configureEditMenuItemGrouping(toolbarMenuItems: MutableList<ContextualToolbarMenuItem>): MutableList<ContextualToolbarMenuItem> {
        //if current tool == freeText add edit button
        //There are 7 items total, and always need to leave room for the color, it has to show.
        //First we need to get all of the items and store them in variables for readability.... rip
        var delete: ContextualToolbarMenuItem? = null
        var color: ContextualToolbarMenuItem? = null

        val edit: ContextualToolbarMenuItem? = if (currentAnnotationModeType == AnnotationType.FREETEXT) {
            ContextualToolbarMenuItem.createSingleItem(context, View.generateViewId(),
                    context.getDrawable(com.pspdfkit.R.drawable.pspdf__ic_edit),
                    context.getString(com.pspdfkit.R.string.pspdf__edit), -1, -1,
                    ContextualToolbarMenuItem.Position.END, false)
        } else null

        for (item in toolbarMenuItems) {
            when (item.title) {
                context.getString(com.pspdfkit.R.string.pspdf__edit_menu_color) -> {
                    color = item
                }
                context.getString(com.pspdfkit.R.string.pspdf__delete) -> {
                    delete = item
                }
            }
        }

        var list = mutableListOf<ContextualToolbarMenuItem>()
        //check to make sure we have all of our items

        // If the user has read/write/manage we want to let them delete (and only delete) non-authored annotations
        val annotation = pdfFragment?.selectedAnnotations?.get(0)
        if (docSession.annotationMetadata.canManage() && annotation?.flags?.contains(AnnotationFlags.LOCKED) == true) {
            // We need to only return a list with the delete menu item
            delete = ContextualToolbarMenuItem.createSingleItem(context, View.generateViewId(),
                    context.getDrawable(R.drawable.vd_trash),
                    context.getString(com.pspdfkit.R.string.pspdf__delete), -1, -1,
                    ContextualToolbarMenuItem.Position.END, false)
            list.add(delete)
        } else {
            if (color != null && delete != null) {
                if (edit != null)
                    list.add(edit)
                list.add(color)
                list.add(delete)
            } else {
                // If we don't have all items, just return the default that we have
                list = toolbarMenuItems
            }
        }


        return list
    }

    val freeTextDialogCallback = object : (Boolean, Boolean, String) -> Unit {
        override fun invoke(cancelled: Boolean, isEditing: Boolean, text: String) {
            if(isEditing && cancelled) return

            val annotation = if (pdfFragment?.selectedAnnotations?.size ?: 0 > 0) pdfFragment?.selectedAnnotations?.get(0) ?: return else return
            if (cancelled && annotation.contents.isNullOrEmpty()) {
                // Remove the annotation
                pdfFragment?.document?.annotationProvider?.removeAnnotationFromPage(annotation)
                pdfFragment?.notifyAnnotationHasChanged(annotation)
                pdfFragment?.clearSelectedAnnotations()
                pdfFragment?.enterAnnotationCreationMode()
                return
            }

            // Updating the annotation's contents triggers an update call which creates the new annotation.
            annotation.contents = text

            // We need to update the UI so pspdfkit knows how to handle this.
            pdfFragment?.exitCurrentlyActiveMode()
            pdfFragment?.enterAnnotationCreationMode()
        }
    }
}
