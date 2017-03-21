package com.david.viewerInterceptor;

/**
 * Created by haojk on 1/25/17.
 */
public enum ProductEventType {

    //新建一个产品
    NEW_PRODUCT(1),
    //删除一个产品
    DEL_PRODUCT(2),
    //修改一个产品
    EDIT_PRODUCT(3),
    //克隆一个产品
    CLONE_PRODUCT(4);

    private int value = 0;

    ProductEventType(int _value) {
        this.value = _value;
    }

    public int getValue() {
        return this.value;
    }
}
