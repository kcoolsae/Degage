import React, { Component } from 'react';
import Modal from 'react-modal'
import UserPicker from './user-picker'

const modalStyles = {
  content : {
    top                   : '25%',
    left                  : '20%',
    right                 : '20%',
    bottom                : '25%',
    backgroundColor       : 'rgba(240, 240, 240, 1)',
    padding               : '0'
  }
};

const UserPickerModal = ({isUserPickerModalOpen, isSaveUserEnabled, onCloseUserPickerModal, onUserSelected}) =>
  <Modal
    isOpen={isUserPickerModalOpen}
    style={modalStyles}
    contentLabel="Modal"
    shouldCloseOnOverlayClick={true}
    onRequestClose={onCloseUserPickerModal}
  >
    <button className='btn btn-primary pull-right' onClick={closeButtonClicked(onCloseUserPickerModal)}>
      <i className='fa fa-close' style={{marginRight: '10px'}}></i>Sluiten
    </button>

    <div style={{padding: '50px'}} className='row'>
      <div className='col-lg-3'><UserPicker /></div>
      <div className='col-lg-3'>{isSaveUserEnabled ?
        <button className='btn btn-primary' onClick={saveUserClicked(onUserSelected)}>
          <i className='fa fa-save' style={{marginRight: '10px'}}></i>Opslaan
        </button> : null}
      </div>
    </div>
  </Modal>

const saveUserClicked = (onUserSelected) => () => onUserSelected()

const closeButtonClicked = (onCloseUserPickerModal) => () => onCloseUserPickerModal()

export default UserPickerModal
