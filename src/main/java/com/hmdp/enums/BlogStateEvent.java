package com.hmdp.enums;

/** 用户操作对应的事件，转换关系在 BlogStateMachineConfig 中声明。 */
public enum BlogStateEvent {
    EDIT,
    SUBMIT,
    APPROVE,
    REJECT
}
