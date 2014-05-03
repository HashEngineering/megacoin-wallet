/*
 * Copyright 2013-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.offline;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bitcoin.protocols.payments.Protos;
import org.bitcoin.protocols.payments.Protos.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.protobuf.ByteString;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.PaymentIntent;
import de.schildbach.wallet.util.Bluetooth;

/**
 * @author Andreas Schildbach
 */
public abstract class DirectPaymentTask
{
	private final Handler backgroundHandler;
	private final Handler callbackHandler;
	private final ResultCallback resultCallback;

	private static final Logger log = LoggerFactory.getLogger(DirectPaymentTask.class);

	public interface ResultCallback
	{
		void onResult(boolean ack);

		void onFail(String message);
	}

	public DirectPaymentTask(@Nonnull final Handler backgroundHandler, @Nonnull final ResultCallback resultCallback)
	{
		this.backgroundHandler = backgroundHandler;
		this.callbackHandler = new Handler(Looper.myLooper());
		this.resultCallback = resultCallback;
	}

	public final static class HttpPaymentTask extends DirectPaymentTask
	{
		private final String url;

		public HttpPaymentTask(@Nonnull final Handler backgroundHandler, @Nonnull final ResultCallback resultCallback, @Nonnull final String url)
		{
			super(backgroundHandler, resultCallback);

			this.url = url;
		}

		@Override
		public void send(@Nonnull final PaymentIntent.Standard standard, @Nonnull final Transaction transaction,
				@Nonnull final Address refundAddress, @Nonnull final BigInteger refundAmount, @Nonnull final byte[] merchantData)
		{
			super.backgroundHandler.post(new Runnable()
			{
				@Override
				public void run()
				{
					if (standard != PaymentIntent.Standard.BIP70)
						throw new IllegalArgumentException("cannot handle: " + standard);

					log.info("trying to send tx {} to {}", new Object[] { transaction.getHashAsString(), url });

					HttpURLConnection connection = null;
					OutputStream os = null;
					InputStream is = null;

					try
					{
						final Payment payment = createPaymentMessage(transaction, refundAddress, refundAmount, null, merchantData);

						connection = (HttpURLConnection) new URL(url).openConnection();

						connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
						connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
						connection.setUseCaches(false);
						connection.setDoInput(true);
						connection.setDoOutput(true);

						connection.setRequestMethod("POST");
						connection.setRequestProperty("Content-Type", Constants.MIMETYPE_PAYMENT);
						connection.setRequestProperty("Accept", Constants.MIMETYPE_PAYMENTACK);
						connection.setRequestProperty("Content-Length", Integer.toString(payment.getSerializedSize()));
						connection.connect();

						os = connection.getOutputStream();
						payment.writeTo(os);
						os.flush();

						log.info("tx {} sent via http", transaction.getHashAsString());

						final int responseCode = connection.getResponseCode();
						if (responseCode == HttpURLConnection.HTTP_OK)
						{
							is = connection.getInputStream();

							final Protos.PaymentACK paymentAck = Protos.PaymentACK.parseFrom(is);

							final boolean ack = !"nack".equals(parsePaymentAck(paymentAck, payment));

							log.info("received {} via http", ack ? "ack" : "nack");

							onResult(ack);
						}
						else
						{
							final String responseMessage = connection.getResponseMessage();
							final String message = "Error " + responseCode + (responseMessage != null ? ": " + responseMessage : "");

							log.info("got http response: " + message);

							onFail(message);
						}
					}
					catch (final IOException x)
					{
						log.info("problem sending", x);

						onFail(x.getMessage());
					}
					finally
					{
						if (os != null)
						{
							try
							{
								os.close();
							}
							catch (final IOException x)
							{
								// swallow
							}
						}

						if (is != null)
						{
							try
							{
								is.close();
							}
							catch (final IOException x)
							{
								// swallow
							}
						}

						if (connection != null)
							connection.disconnect();
					}
				}
			});
		}
	}

	public final static class BluetoothPaymentTask extends DirectPaymentTask
	{
		private final BluetoothAdapter bluetoothAdapter;
		private final String bluetoothMac;

		public BluetoothPaymentTask(@Nonnull final Handler backgroundHandler, @Nonnull final ResultCallback resultCallback,
				@Nonnull final BluetoothAdapter bluetoothAdapter, @Nonnull final String bluetoothMac)
		{
			super(backgroundHandler, resultCallback);

			this.bluetoothAdapter = bluetoothAdapter;
			this.bluetoothMac = bluetoothMac;
		}

		@Override
		public void send(@Nonnull final PaymentIntent.Standard standard, @Nonnull final Transaction transaction,
				@Nonnull final Address refundAddress, @Nonnull final BigInteger refundAmount, @Nonnull final byte[] merchantData)
		{
			super.backgroundHandler.post(new Runnable()
			{
				@Override
				public void run()
				{
					log.info("trying to send tx {} via bluetooth {} using {} standard", new Object[] { transaction.getHashAsString(), bluetoothMac,
							standard });

					final byte[] serializedTx = transaction.unsafeBitcoinSerialize();

					BluetoothSocket socket = null;
					DataOutputStream os = null;
					DataInputStream is = null;

					try
					{
						final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(Bluetooth.decompressMac(bluetoothMac));

						final boolean ack;

						if (standard == PaymentIntent.Standard.BIP21)
						{
							socket = device.createInsecureRfcommSocketToServiceRecord(Bluetooth.BLUETOOTH_UUID_CLASSIC);

							socket.connect();
							log.info("connected to classic {}", bluetoothMac);

							is = new DataInputStream(socket.getInputStream());
							os = new DataOutputStream(socket.getOutputStream());

							os.writeInt(1);
							os.writeInt(serializedTx.length);
							os.write(serializedTx);

							os.flush();

							log.info("tx {} sent via bluetooth", transaction.getHashAsString());

							ack = is.readBoolean();
						}
						else if (standard == PaymentIntent.Standard.BIP70)
						{
							socket = device.createInsecureRfcommSocketToServiceRecord(Bluetooth.BLUETOOTH_UUID_PAYMENT_PROTOCOL);

							socket.connect();
							log.info("connected to payment protocol {}", bluetoothMac);

							is = new DataInputStream(socket.getInputStream());
							os = new DataOutputStream(socket.getOutputStream());

							final Payment payment = createPaymentMessage(transaction, refundAddress, refundAmount, null, merchantData);
							payment.writeDelimitedTo(os);
							os.flush();

							log.info("tx {} sent via bluetooth", transaction.getHashAsString());

							final Protos.PaymentACK paymentAck = Protos.PaymentACK.parseDelimitedFrom(is);

							ack = "ack".equals(parsePaymentAck(paymentAck, payment));
						}
						else
						{
							throw new IllegalArgumentException("cannot handle: " + standard);
						}

						log.info("received {} via bluetooth", ack ? "ack" : "nack");

						onResult(ack);
					}
					catch (final IOException x)
					{
						log.info("problem sending", x);

						onFail(x.getMessage());
					}
					finally
					{
						if (os != null)
						{
							try
							{
								os.close();
							}
							catch (final IOException x)
							{
								// swallow
							}
						}

						if (is != null)
						{
							try
							{
								is.close();
							}
							catch (final IOException x)
							{
								// swallow
							}
						}

						if (socket != null)
						{
							try
							{
								socket.close();
							}
							catch (final IOException x)
							{
								// swallow
							}
						}
					}
				}
			});
		}
	}

	public abstract void send(@Nonnull PaymentIntent.Standard standard, @Nonnull Transaction transaction, @Nonnull Address refundAddress,
			@Nonnull BigInteger refundAmount, @Nonnull byte[] merchantData);

	protected void onResult(final boolean ack)
	{
		callbackHandler.post(new Runnable()
		{
			@Override
			public void run()
			{
				resultCallback.onResult(ack);
			}
		});
	}

	protected void onFail(final String message)
	{
		callbackHandler.post(new Runnable()
		{
			@Override
			public void run()
			{
				resultCallback.onFail(message);
			}
		});
	}

	private static Payment createPaymentMessage(@Nonnull final Transaction transaction, @Nullable final Address refundAddress,
			@Nullable final BigInteger refundAmount, @Nullable final String memo, @Nullable final byte[] merchantData) throws IOException
	{
		final Protos.Payment.Builder builder = Protos.Payment.newBuilder();

		builder.addTransactions(ByteString.copyFrom(transaction.unsafeBitcoinSerialize()));

		if (refundAddress != null)
		{
			if (refundAmount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
				throw new IllegalArgumentException("refund amount too big for protobuf: " + refundAmount);

			final Protos.Output.Builder refundOutput = Protos.Output.newBuilder();
			refundOutput.setAmount(refundAmount.longValue());
			refundOutput.setScript(ByteString.copyFrom(ScriptBuilder.createOutputScript(refundAddress).getProgram()));
			builder.addRefundTo(refundOutput);
		}

		if (memo != null)
			builder.setMemo(memo);

		if (merchantData != null)
			builder.setMerchantData(ByteString.copyFrom(merchantData));

		return builder.build();
	}

	private static String parsePaymentAck(@Nonnull final Protos.PaymentACK paymentAck, @Nonnull final Payment expectedPaymentMessage)
			throws IOException
	{
		if (!paymentAck.getPayment().equals(expectedPaymentMessage))
			return null;

		return paymentAck.getMemo();
	}
}
