/*
 * Copyright (C) 2017 - present  Instructure, Inc.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.instructure.teacher.fragments

import android.support.v7.widget.RecyclerView
import android.view.View
import com.instructure.canvasapi2.models.Attachment
import com.instructure.canvasapi2.models.Submission
import com.instructure.pandautils.fragments.BaseSyncFragment
import com.instructure.teacher.R
import com.instructure.teacher.adapters.AttachmentAdapter
import com.instructure.teacher.factory.SpeedGraderFilesPresenterFactory
import com.instructure.teacher.holders.AttachmentViewHolder
import com.instructure.teacher.presenters.SpeedGraderFilesPresenter
import com.instructure.pandautils.utils.NullableParcelableArg
import com.instructure.teacher.utils.RecyclerViewUtils
import com.instructure.teacher.view.SubmissionFileSelectedEvent
import com.instructure.teacher.view.SubmissionSelectedEvent
import com.instructure.teacher.viewinterface.SpeedGraderFilesView
import kotlinx.android.synthetic.main.fragment_speedgrader_files.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class SpeedGraderFilesFragment : BaseSyncFragment<
        Attachment,
        SpeedGraderFilesPresenter,
        SpeedGraderFilesView,
        AttachmentViewHolder,
        AttachmentAdapter>(), SpeedGraderFilesView {

    lateinit private var mRecyclerView: RecyclerView
    private var mSubmission: Submission? by NullableParcelableArg(default = Submission())

    companion object {
        @JvmStatic
        fun newInstance(submission: Submission?) = SpeedGraderFilesFragment().apply {
            mSubmission = submission
        }
    }

    override fun getList() = presenter.data
    override fun getRecyclerView(): RecyclerView = speedGraderFilesRecyclerView
    override fun layoutResId() = R.layout.fragment_speedgrader_files
    override fun getPresenterFactory() = SpeedGraderFilesPresenterFactory(mSubmission)
    override fun onCreateView(view: View) = Unit
    override fun onPresenterPrepared(presenter: SpeedGraderFilesPresenter) {
        mRecyclerView = RecyclerViewUtils.buildRecyclerView(mRootView, context, adapter, presenter, R.id.swipeRefreshLayout,
                R.id.speedGraderFilesRecyclerView, R.id.speedGraderFilesEmptyView, getString(R.string.no_items_to_display_short))
        swipeRefreshLayout.isEnabled = false
    }

    override fun onReadySetGo(presenter: SpeedGraderFilesPresenter) {
        if (mAdapter == null) {
            mRecyclerView.adapter = adapter
        }
        presenter.loadData(false)
    }

    override fun getAdapter(): AttachmentAdapter {
        if (mAdapter == null) {
            mAdapter = AttachmentAdapter(context, presenter) {
                EventBus.getDefault().post(SubmissionFileSelectedEvent(presenter?.getSubmission()?.id ?: -1, it))
            }
        }
        return mAdapter
    }

    override fun checkIfEmpty() {
        RecyclerViewUtils.checkIfEmpty(speedGraderFilesEmptyView, mRecyclerView, swipeRefreshLayout, adapter, presenter.isEmpty)
    }

    override fun onRefreshFinished() {}
    override fun onRefreshStarted() {}

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSubmissionSelected(event: SubmissionSelectedEvent) {
        if (event.submission?.id == presenter?.getSubmission()?.id) {
            presenter?.setSubmission(event.submission)
            adapter.setSelectedPosition(0)
        }
    }
}
