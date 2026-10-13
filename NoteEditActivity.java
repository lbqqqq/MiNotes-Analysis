package com.miui.notes;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import com.miui.notes.model.NoteColumns;
import com.miui.notes.model.WorkingNote;

/**
 * 【精读注释说明】
 * 类：NoteEditActivity 便签编辑页面
 * 所属模块：ui界面模块
 * 功能：新增、修改便签文本，保存便签到数据库
 * 架构：MVC，编辑视图，操作WorkingNote模型
 * 编程要领：
 * 1. onPause生命周期保存数据：切后台自动保存，防止意外丢失笔记
 * 2. WorkingNote封装所有数据库CRUD，UI层不直接写SQL，解耦
 * 3. 区分新建便签 / 编辑已有便签两种场景
 * 4. 菜单提供保存、分享功能
 * 代码行数：873行，MiNotes核心编辑模块
 */
public class NoteEditActivity extends Activity {
    // 常量定义
    private static final int MENU_SAVE = 1001;
    private static final int MENU_SHARE = 1002;

    private EditText mEditText;
    private TextView mTitleView;
    private WorkingNote mWorkingNote;
    private boolean mIsNewNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_edit);
        initView();
        parseIntent();
    }

    /**
     * 初始化编辑页面控件
     */
    private void initView() {
        mEditText = findViewById(R.id.note_editor);
        mTitleView = findViewById(R.id.note_title);
    }

    /**
     * 解析跳转Intent，判断是新建笔记还是编辑已有笔记
     */
    private void parseIntent() {
        Intent intent = getIntent();
        long noteId = intent.getLongExtra(NoteColumns._ID, -1);
        if (noteId == -1) {
            // noteId=-1：新建便签
            mIsNewNote = true;
            mWorkingNote = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_NORMAL,
                    ResourceParser.RED);
        } else {
            // 传入id：读取数据库，加载已有便签
            mIsNewNote = false;
            mWorkingNote = WorkingNote.loadNote(this, noteId);
            // 将笔记内容填充到输入框
            mTitleView.setText(mWorkingNote.getTitle());
            mEditText.setText(mWorkingNote.getWorkingText());
        }
    }

    /**
     * 页面暂停（切后台、锁屏）自动保存笔记，保障数据安全
     * 【编程要领】利用Android生命周期做自动保存，用户不需要手动点保存
     */
    @Override
    protected void onPause() {
        super.onPause();
        saveNoteToModel();
        if (mWorkingNote != null) {
            mWorkingNote.saveNote();
        }
    }

    /**
     * 将输入框文本写入WorkingNote模型对象
     */
    private void saveNoteToModel() {
        String content = mEditText.getText().toString();
        String title = mTitleView.getText().toString();
        mWorkingNote.setTitle(title);
        mWorkingNote.setWorkingText(content);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SAVE, 0, R.string.save);
        menu.add(0, MENU_SHARE, 1, R.string.share);
        return true;
    }

    /**
     * 菜单栏点击事件
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SAVE:
                saveNoteToModel();
                mWorkingNote.saveNote();
                finish();
                return true;
            case MENU_SHARE:
                shareNoteContent();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 分享便签文本，调用系统分享Intent
     */
    private void shareNoteContent() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, mWorkingNote.getWorkingText());
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_note)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放WorkingNote资源
        if(mWorkingNote != null){
            mWorkingNote.release();
        }
    }
}
