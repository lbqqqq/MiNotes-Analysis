package com.miui.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncQueryHandler;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.miui.notes.model.NoteColumns;
import com.miui.notes.model.Notes;
import com.miui.notes.model.WorkingNote;
import com.miui.notes.widget.NotesListAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 【精读注释说明】
 * 类：NotesListActivity 便签列表主页面
 * 所属模块：ui界面模块
 * 功能：展示便签/文件夹列表、文件夹切换、多选批量操作、初始化引导便签
 * 架构：MVC，Activity作为View层，通过ContentResolver访问SQLite数据模型
 * 编程要领：
 * 1. Android主线程禁止数据库阻塞查询，使用AsyncQueryHandler异步数据库查询
 * 2. 使用枚举管理页面多种状态，替代零散布尔变量，减少状态bug
 * 3. 常量集中定义，避免硬编码魔法数字、硬编码SQL字符串
 * 4. 职责拆分：onCreate只做初始化调用，复杂逻辑抽成独立方法
 * 5. 长按唤起ActionMode实现批量多选，Android早期标准批量UI方案
 * 代码行数：954行，是MiNotes老版开源项目的核心入口页面
 */
public class NotesListActivity extends Activity implements View.OnClickListener, AdapterView.OnItemLongClickListener {
    // ============ 常量定义区 全部static final大写，规范Java编码风格 ============
    // 异步查询Token，区分不同数据库查询任务
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0;
    private static final int FOLDER_LIST_QUERY_TOKEN = 1;
    // SharedPreferences键：标记是否已经创建首次引导便签
    private static final String PREFERENCE_ADD_INTRODUCTION = "add_introduction";

    // SQL查询条件常量，复用字符串，避免多处重复手写SQL，减少拼写错误
    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";
    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    /**
     * 页面状态枚举：管理列表三种展示模式
     * NOTE_LIST：普通便签列表；SUB_FOLDER：子文件夹；CALL_RECORD_FOLDER：通话记录文件夹
     * 优点：相比多个boolean变量，状态唯一，可读性强，防止状态冲突
     */
    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    }

    // ============ 成员变量 ============
    private ListEditState mState;
    private ListView mListView;
    private NotesListAdapter mListAdapter;
    private AsyncQueryHandler mAsyncQueryHandler;
    private long mCurrentFolderId; // 当前打开文件夹ID
    private ActionMode mActionMode; // 批量操作模式
    private boolean mIsMultiSelect = false; // 是否开启多选模式

    /**
     * Activity生命周期：页面创建时调用
     * 设计习惯：onCreate方法保持简短，初始化逻辑拆分到initResources()
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 加载布局文件 note_list.xml
        setContentView(R.layout.note_list);
        // 初始化控件、适配器、异步查询器、事件监听
        initResources();
        // 判断是否需要创建引导便签（第一次打开APP才执行）
        setAppInfoFromRawRes();
        // 默认加载根文件夹
        loadFolderData(Notes.ID_ROOT_FOLDER);
    }

    /**
     * 初始化资源、控件、适配器、异步查询处理器
     */
    private void initResources() {
        mListView = findViewById(R.id.note_list_view);
        mListView.setOnItemLongClickListener(this);
        mListView.setOnClickListener(this);
        mListAdapter = new NotesListAdapter(this, null, 0);
        mListView.setAdapter(mListAdapter);

        // AsyncQueryHandler：封装ContentResolver，异步数据库查询，回调在主线程
        // 核心优化：数据库IO在子线程，查询完成后自动回调到UI线程更新列表，不会ANR
        mAsyncQueryHandler = new AsyncQueryHandler(getContentResolver()) {
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                super.onQueryComplete(token, cookie, cursor);
                if (token == FOLDER_NOTE_LIST_QUERY_TOKEN) {
                    // 查询完成，把Cursor交给Adapter，刷新列表UI
                    mListAdapter.changeCursor(cursor);
                }
            }
        };
    }

    /**
     * 加载指定文件夹下的便签数据，发起异步数据库查询
     * @param folderId 目标文件夹id
     */
    private void loadFolderData(long folderId) {
        mCurrentFolderId = folderId;
        ContentResolver cr = getContentResolver();
        String[] projection = {
                NoteColumns._ID,
                NoteColumns.TITLE,
                NoteColumns.SNIPPET,
                NoteColumns.MODIFIED_DATE,
                NoteColumns.PARENT_ID
        };
        String selection;
        String[] selectionArgs;

        if (folderId == Notes.ID_ROOT_FOLDER) {
            // 根文件夹使用特殊查询条件
            selection = ROOT_FOLDER_SELECTION;
            selectionArgs = new String[]{String.valueOf(folderId)};
            mState = ListEditState.SUB_FOLDER;
        } else {
            // 普通子文件夹，查询parent_id等于当前文件夹id的便签
            selection = NORMAL_SELECTION;
            selectionArgs = new String[]{String.valueOf(folderId)};
            mState = ListEditState.NOTE_LIST;
        }
        // 发起异步查询，子线程执行数据库查询，不阻塞UI
        mAsyncQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_URI, projection, selection, selectionArgs, NoteColumns.MODIFIED_DATE + " DESC");
    }

    /**
     * 【重点精读方法】首次打开APP，读取raw目录引导文本，创建第一条示例便签
     * 编程要点：
     * 1. 使用SharedPreferences标记初始化状态，只执行一次
     * 2. IO流读取资源文件，finally代码块保证流关闭，防止资源泄漏
     * 3. 使用StringBuilder拼接长文本，避免字符串+=频繁新建对象（性能优化）
     */
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        // 判断标记，false代表是第一次启动
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                // 读取res/raw/introduction文本文件
                in = getResources().openRawResource(R.raw.introduction);
                if (in != null) {
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char[] buf = new char[1024]; // 设置1024字符缓冲区，减少IO次数
                    int len = 0;
                    // 循环读取缓冲区内容，直到读完
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len);
                    }
                }
            } catch (IOException e) {
                // IO异常捕获，打印堆栈，不直接崩溃APP
                e.printStackTrace();
                return;
            } finally {
                // finally块：无论正常结束还是异常，都关闭输入流
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            // 创建空白便签实体
            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_NORMAL,
                    ResourceParser.RED);
            // 设置便签文本
            note.setWorkingText(sb.toString());
            // 保存到数据库，保存成功后标记为已初始化
            if (note.saveNote()) {
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            }
        }
    }

    /**
     * 列表条目长按事件：开启多选ActionMode批量操作
     */
    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (!mIsMultiSelect) {
            mIsMultiSelect = true;
            // 唤起顶部批量操作栏
            mActionMode = startActionMode(new BatchActionCallback());
        }
        mListAdapter.toggleItemSelect(position);
        return true;
    }

    /**
     * 点击事件：普通点击打开编辑页面；多选模式点击切换选中状态
     */
    @Override
    public void onClick(View v) {
        int pos = mListView.getPositionForView(v);
        if (mIsMultiSelect) {
            mListAdapter.toggleItemSelect(pos);
        } else {
            // 普通点击，跳转到NoteEditActivity编辑页面
            Cursor cursor = (Cursor) mListAdapter.getItem(pos);
            long noteId = cursor.getLong(cursor.getColumnIndex(NoteColumns._ID));
            Intent intent = new Intent(this, NoteEditActivity.class);
            intent.putExtra(NoteColumns._ID, noteId);
            startActivity(intent);
        }
    }

    /**
     * 批量操作ActionMode回调：菜单【删除/移动】
     */
    private class BatchActionCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.batch_note_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.menu_batch_delete) {
                deleteSelectedNotes();
                return true;
            } else if (item.getItemId() == R.id.menu_batch_move) {
                moveSelectedNotes();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mIsMultiSelect = false;
            mListAdapter.clearSelection();
        }
    }

    /**
     * 批量删除选中便签
     */
    private void deleteSelectedNotes() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        long[] selectedIds = mListAdapter.getSelectedNoteIds();
                        WorkingNote.batchDelete(NotesListActivity.this, selectedIds);
                        mActionMode.finish();
                        Toast.makeText(NotesListActivity.this, R.string.delete_success, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 批量移动便签到其他文件夹
     */
    private void moveSelectedNotes() {
        // 弹出文件夹选择弹窗，实现移动逻辑
        Toast.makeText(this, "移动文件夹", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面销毁，关闭Cursor，释放数据库资源，防止内存泄漏
        Cursor c = mListAdapter.getCursor();
        if (c != null && !c.isClosed()) {
            c.close();
        }
    }
}
