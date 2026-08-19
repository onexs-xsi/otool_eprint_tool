package com.otool.eprint.ui;

import android.text.Editable;
import android.text.TextWatcher;

/** 只需 afterTextChanged 的 TextWatcher 基类。 */
public abstract class SimpleTextWatcher implements TextWatcher {
    @Override
    public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence value, int start, int before, int count) {}
}
